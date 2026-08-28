#!/usr/bin/env bash
# Drives one end-to-end review against a running stack. Architecture §31, §37.
#
# Exercises the real §20.1 and §20.2 flows through the PUBLIC API only — the same requests the
# browser makes. Nothing here reaches into a database or a container: if this script succeeds, the
# product works for a user.
#
#   1. create a standard              POST /api/admin/standards
#   2. upload an immutable version    POST /api/admin/standards/{id}/versions   -> indexing job
#   3. wait for INDEXED               requirement extraction + embeddings (real LiteLLM)
#   4. activate the version           §8.1 refuses activation unless INDEXED
#   5. upload a process document      POST /api/uploads                         -> parsing job
#   6. wait for readyForComparison    spans must exist, or every requirement reads MISSING (§17.5)
#   7. create the comparison          POST /api/comparisons                     -> comparison job
#   8. wait for a terminal state      then print the shareable URL
#
# set -u is deliberately ABSENT: this sources .env, which legitimately contains blank optional values
# (LITELLM_RERANKER_MODEL), and -u would abort on them.
set -eo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

STANDARD_PDF="${STANDARD_PDF:-$REPO_ROOT/demo/standards/change-management-standard.pdf}"
PROCESS_PDF="${PROCESS_PDF:-$REPO_ROOT/demo/processes/release-management-process.pdf}"

if [ -f "$REPO_ROOT/.env" ]; then
  set -a; . "$REPO_ROOT/.env"; set +a
fi

ADMIN_AUTH="admin:${DEMO_ADMIN_PASSWORD:?DEMO_ADMIN_PASSWORD must be set}"
REVIEWER_AUTH="reviewer:${DEMO_REVIEWER_PASSWORD:?DEMO_REVIEWER_PASSWORD must be set}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
fail() { printf '\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }

# jq is not assumed — parsing JSON with grep breaks on whitespace changes, so python3 is used for
# every extraction instead.
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

for f in "$STANDARD_PDF" "$PROCESS_PDF"; do
  [ -f "$f" ] || fail "missing fixture: $f (run infrastructure/scripts/text-to-pdf.py first)"
done

say "Waiting for $BASE_URL/healthz"
for _ in $(seq 1 60); do
  curl -sf "$BASE_URL/healthz" >/dev/null 2>&1 && break
  sleep 2
done
curl -sf "$BASE_URL/healthz" >/dev/null || fail "review-web never became reachable"

# ---------------------------------------------------------------------------------------------
say "1. Creating standard"
STANDARD_KEY="GRC-CHG-$(date +%s)"
STANDARD_ID=$(curl -sf -u "$ADMIN_AUTH" -X POST "$BASE_URL/api/admin/standards" \
  -H 'Content-Type: application/json' \
  -d "{\"standardKey\":\"$STANDARD_KEY\",\"title\":\"Technology Change Management Standard\",\"owner\":\"Technology Governance\",\"description\":\"Mandatory controls for production change authorisation, testing, and deployment.\",\"category\":\"Change Management\"}" \
  | field "['id']") || fail "could not create standard"
echo "   standardId=$STANDARD_ID key=$STANDARD_KEY"

say "2. Uploading standard version 2.4 (triggers extraction + indexing)"
VERSION_JSON=$(curl -sf -u "$ADMIN_AUTH" -X POST "$BASE_URL/api/admin/standards/$STANDARD_ID/versions" \
  -F "version=2.4" -F "file=@$STANDARD_PDF") || fail "could not upload standard version"
VERSION_ID=$(echo "$VERSION_JSON" | field "['id']")
echo "   standardVersionId=$VERSION_ID"

say "3. Waiting for indexing (real LiteLLM extraction + embeddings; this is the slow step)"
for i in $(seq 1 150); do
  V=$(curl -sf -u "$ADMIN_AUTH" "$BASE_URL/api/admin/standards/$STANDARD_ID")
  STATE=$(echo "$V" | field "['versions'][0]['indexingState']")
  COUNT=$(echo "$V" | field "['versions'][0]['requirementCount'] or 0")
  printf '\r   [%3ds] %s (%s requirements)   ' "$((i*4))" "$STATE" "$COUNT"
  case "$STATE" in
    INDEXED) echo; break ;;
    # NEEDS_REVIEW is a real terminal outcome (§12.8), not a failure — but §8.1 will refuse to
    # activate it, so the run cannot continue.
    FAILED|NEEDS_REVIEW)
      echo
      echo "$V" | python3 -c "import json,sys; print('   message:', json.load(sys.stdin)['versions'][0].get('indexingMessage'))"
      fail "indexing ended in $STATE" ;;
  esac
  sleep 4
done
[ "$STATE" = "INDEXED" ] || fail "indexing did not finish in time (last state: $STATE)"

say "4. Activating the version"
curl -sf -u "$ADMIN_AUTH" -X PATCH "$BASE_URL/api/admin/standards/versions/$VERSION_ID/lifecycle" \
  -H 'Content-Type: application/json' -d '{"lifecycle":"ACTIVE"}' \
  >/dev/null || fail "could not activate (§8.1 requires INDEXED)"
echo "   ACTIVE"

# ---------------------------------------------------------------------------------------------
say "5. Uploading process document"
DOC_JSON=$(curl -sf -u "$REVIEWER_AUTH" -X POST "$BASE_URL/api/uploads" \
  -F "file=@$PROCESS_PDF") || fail "could not upload process document"
DOC_ID=$(echo "$DOC_JSON" | field "['documentVersionId']")
echo "   documentVersionId=$DOC_ID"

say "6. Waiting for parsing"
for i in $(seq 1 60); do
  D=$(curl -sf -u "$REVIEWER_AUTH" "$BASE_URL/api/uploads/$DOC_ID")
  READY=$(echo "$D" | field "['readyForComparison']")
  DSTATE=$(echo "$D" | field "['state']")
  SPANS=$(echo "$D" | field "['spanCount'] or 0")
  printf '\r   [%3ds] %s (%s passages)   ' "$((i*3))" "$DSTATE" "$SPANS"
  [ "$READY" = "True" ] && { echo; break; }
  [ "$DSTATE" = "FAILED" ] && { echo; fail "parsing failed"; }
  sleep 3
done
[ "$READY" = "True" ] || fail "document never became comparable"

# ---------------------------------------------------------------------------------------------
say "7. Creating comparison"
CMP_JSON=$(curl -sf -u "$REVIEWER_AUTH" -X POST "$BASE_URL/api/comparisons" \
  -H 'Content-Type: application/json' \
  -d "{\"documentVersionId\":\"$DOC_ID\",\"standardVersionIds\":[\"$VERSION_ID\"]}") \
  || fail "could not create comparison"
CMP_ID=$(echo "$CMP_JSON" | field "['publicId']")
echo "   comparisonId=$CMP_ID"

say "8. Waiting for evaluation (one model call per requirement)"
for i in $(seq 1 150); do
  C=$(curl -sf -u "$REVIEWER_AUTH" "$BASE_URL/api/comparisons/$CMP_ID")
  CSTATE=$(echo "$C" | field "['status']")
  DONE=$(echo "$C" | field "['evaluatedRequirements'] or 0")
  TOTAL=$(echo "$C" | field "['totalRequirements'] or 0")
  printf '\r   [%3ds] %s (%s/%s)   ' "$((i*4))" "$CSTATE" "$DONE" "$TOTAL"
  case "$CSTATE" in
    COMPLETED|PARTIALLY_COMPLETED|FAILED) echo; break ;;
  esac
  sleep 4
done

say "Result"
curl -sf -u "$REVIEWER_AUTH" "$BASE_URL/api/comparisons/$CMP_ID" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f\"  status              {d['status']}\")
print(f\"  evaluated           {d.get('evaluatedRequirements')} / {d.get('totalRequirements')}\")
print(f\"  model alias         {d.get('comparisonModelAlias')}\")
print(f\"  prompt / reqset     {d.get('promptVersion')} / {d.get('requirementSetVersion')}\")
if d.get('stateMessage'):
    print(f\"  note                {d['stateMessage']}\")
for ref in d['references']:
    findings = ref['findings']
    print(f\"\n  {ref.get('standardTitle')} v{ref.get('standardVersion')} - {len(findings)} finding(s)\")
    for f in findings:
        gap = f\" [{f['gapType']}]\" if f.get('gapType') else ''
        print(f\"    {f['status']:22}{gap:26} {f['requirementId']:10} {f.get('requirementTitle','')}\")
        if f.get('recommendedAction'):
            print(f\"      -> {f['recommendedAction'][:100]}\")
"

printf '\n\033[1;32mOpen in the browser:\033[0m %s/comparisons/%s\n' "$BASE_URL" "$CMP_ID"
printf '\033[1mLogin:\033[0m reviewer / %s\n\n' "$DEMO_REVIEWER_PASSWORD"
