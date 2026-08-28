#!/usr/bin/env python3
"""Scores a shadow extraction against human-reviewed condition roles, across the whole corpus.

WHAT THIS MEASURES
------------------
The reviewed batches are ground truth: conditions a person classified after the extractor called them
all SCOPE_GATE and was wrong 113 times out of 115. This asks whether a prompt change moved the extractor
toward those judgements, and reports it as a table rather than a verdict.

THE METRIC THAT DECIDES RELEASE IS FALSE EXCLUSION ELIGIBILITY
--------------------------------------------------------------
Not overall accuracy. Only a SCOPE_GATE can make a requirement NOT_APPLICABLE, and only when a governed
override authorises it — so the question that matters is how many conditions the extractor would make
ELIGIBLE to exclude that a human said were not scope boundaries. A false CONDITIONAL_OBLIGATION costs one
extra comparison; a false exclusion drops a control from a review and produces a clean-looking report
that is wrong. The two are not commensurable and a single accuracy figure hides exactly that asymmetry.

WHAT THIS DOES NOT DO
---------------------
It changes nothing. No override is written, no set is promoted, no reviewed decision is touched. A score
is evidence for a human decision about the prompt, not authority to overwrite a review.

USAGE
  infrastructure/scripts/score-shadow-extraction.py --shadow-set REQSET-V5
  infrastructure/scripts/score-shadow-extraction.py --shadow-set REQSET-V5 \
      --standard-version <id>          # one standard only
"""
import argparse
import json
import subprocess
import sys
from collections import Counter, defaultdict

MONGO_CONTAINER = "docreview-mongodb-1"
MONGO_DB = "retrieval_comparison"

# Only a scope gate can exclude a requirement, so this is the class whose errors are asymmetric.
EXCLUDING_ROLE = "SCOPE_GATE"

# Bare connectives that introduce a condition without stating one. Mirrors ExtractionValidator rule 11
# (REQ-SCHEMA-V2); duplicated here rather than imported because this is a reporting script, and a
# fragment rate computed from a different list than the validator enforces would be misleading.
CONNECTIVE_ONLY = {
    "when", "when it", "when it is", "when they", "when they are", "when the",
    "if", "if it", "if it is", "if the", "where", "where it", "where it is", "where the",
    "for", "for a", "for an", "for the", "unless", "unless it", "unless the",
    "in the case of", "in cases where", "provided that", "subject to", "such as", "including",
}

# Phrases the review found contestable — the ones a policy owner was asked about. Tracked separately
# because agreement on a settled phrase says less than agreement on one that was genuinely unclear.
CONTESTED_PHRASES = {
    "when it is",
    "for all remote access to production",
    "all production changes",
    "material changes",
}


def mongo(script: str):
    """Runs a mongosh script and returns parsed JSON from its single print."""
    wrapper = (
        'exec mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" '
        f"--authenticationDatabase admin {MONGO_DB}")
    proc = subprocess.run(
        ["docker", "exec", "-i", MONGO_CONTAINER, "sh", "-c", wrapper],
        input=script, text=True, capture_output=True)
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        raise SystemExit("mongosh failed")
    # EARLIEST bracket, not the latest. mongosh prefixes each line with its prompt, so the payload
    # starts at the first `[` or `{`; taking the later of the two sliced into the middle of the array
    # at the first object and produced a parse error that read as "no JSON".
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        candidates = [i for i in (stripped.find("["), stripped.find("{")) if i >= 0]
        if not candidates:
            continue
        try:
            return json.loads(stripped[min(candidates):])
        except Exception:
            continue
    raise SystemExit("no JSON in mongosh output")


def fetch_truth(version_filter):
    """Reviewed decisions, keyed by (versionId, requirementId, normalised text).

    The version id is IN THE KEY. Requirement ids like "CHG-01-2" recur across standards, so a key of
    (requirementId, text) silently collapsed 115 reviewed rows into 78 — dropping 37 and risking a
    condition being scored against a different standard's decision.

    Only ACTIVE rows: a RETIRED override was superseded, and scoring against a decision that no longer
    governs would measure the extractor against history rather than against current policy.
    """
    match = '{reviewStatus: {$ne: "RETIRED"}'
    if version_filter:
        match += f", standardVersionId: {json.dumps(version_filter)}"
    match += "}"
    rows = mongo(
        f'print(JSON.stringify(db.requirement_condition_overrides.find({match}, '
        '{_id:0, standardVersionId:1, requirementId:1, conditionText:1, reviewedRole:1, '
        'reviewStatus:1, extractedRole:1, canDriveNotApplicable:1, reviewedAt:1}).toArray()));')

    # Newest wins where a phrase was re-reviewed in a later batch, matching the runtime resolver.
    truth = {}
    for r in sorted(rows, key=lambda r: str(r.get("reviewedAt", ""))):
        key = (r["standardVersionId"], r["requirementId"], r["conditionText"].strip().lower())
        truth[key] = r
    return truth


def fetch_shadow(shadow_set, version_filter, by_prompt=False):
    """Conditions from the shadow set, keyed identically.

    `by_prompt` selects on requirementExtractionPromptVersion instead of the set name. Necessary because
    the set counter increments PER STANDARD VERSION: one shadow run across ten standards produced nine
    sets named REQSET-V4 and one named REQSET-V5, so a set-name filter would silently score nine
    standards under the wrong label and miss the tenth entirely.
    """
    field = "requirementExtractionPromptVersion" if by_prompt else "requirementSetVersion"
    if by_prompt:
        # The prompt version lives on the set-version record, not on each requirement, so resolve the
        # matching set names first and select requirements by those.
        sets = mongo(
            f'print(JSON.stringify(db.requirement_set_versions.find('
            f'{{{field}: {json.dumps(shadow_set)}, validationStatus: "SHADOW"}}, '
            '{_id:0, standardVersionId:1, requirementSetVersion:1}).toArray()));')
        pairs = [(s["standardVersionId"], s["requirementSetVersion"]) for s in sets
                 if not version_filter or s["standardVersionId"] == version_filter]
        if not pairs:
            return {}
        clauses = ", ".join(
            f'{{standardVersionId: {json.dumps(sv)}, requirementSetVersion: {json.dumps(rs)}}}'
            for sv, rs in pairs)
        match = f"{{$or: [{clauses}]}}"
    else:
        match = f"{{requirementSetVersion: {json.dumps(shadow_set)}"
        if version_filter:
            match += f", standardVersionId: {json.dumps(version_filter)}"
        match += "}"
    rows = mongo(
        f'print(JSON.stringify(db.standard_requirements.find({match}, '
        '{_id:0, standardVersionId:1, requirementId:1, '
        '"applicability.structuredConditions":1}).toArray()));')

    shadow = {}
    for r in rows:
        for c in ((r.get("applicability") or {}).get("structuredConditions") or []):
            text = (c.get("text") or "").strip()
            if not text:
                continue
            key = (r["standardVersionId"], r["requirementId"], text.lower())
            shadow[key] = {
                "role": c.get("role") or "UNKNOWN",
                "documentTypes": c.get("documentTypes") or [],
                "text": text,
            }
    return shadow


def is_fragment(text):
    return text.strip().lower().rstrip(" ,;:.") in CONNECTIVE_ONLY


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--shadow-set", required=True,
                    help="requirementSetVersion, or the prompt version with --by-prompt")
    ap.add_argument("--by-prompt", action="store_true",
                    help="treat --shadow-set as a prompt version. Needed for a multi-standard run: "
                         "the set counter is per standard, so one run yields several set names.")
    ap.add_argument("--standard-version", default=None,
                    help="limit to one standardVersionId. Omit to score the whole corpus.")
    args = ap.parse_args()

    truth = fetch_truth(args.standard_version)
    shadow = fetch_shadow(args.shadow_set, args.standard_version, args.by_prompt)

    scope = args.standard_version or "ALL STANDARDS"
    print(f"shadow set: {args.shadow_set}    scope: {scope}")
    print()
    print(f"  reviewed conditions (ground truth)   {len(truth)}")
    print(f"  shadow conditions extracted          {len(shadow)}")

    matched = {k: v for k, v in shadow.items() if k in truth}
    print(f"  matched to a reviewed decision       {len(matched)}")
    print(f"  shadow-only (not scoreable)          {len(shadow) - len(matched)}")
    print(f"  reviewed but not re-extracted        {len(truth) - len(matched)}")

    # Corpus-wide extraction quality, independent of the labelled set.
    unknown = sum(1 for v in shadow.values() if v["role"] == "UNKNOWN")
    fragments = sum(1 for v in shadow.values() if is_fragment(v["text"]))
    print()
    print("EXTRACTION QUALITY (all shadow conditions, labelled or not)")
    print(f"  UNKNOWN                              {unknown}"
          f"  ({pct(unknown, len(shadow))})")
    print(f"  condition fragments                  {fragments}"
          f"  ({pct(fragments, len(shadow))})"
          "   <- rule 11 rejects these at extraction")

    if not matched:
        print("\nNothing scoreable — condition texts did not align. Compare chunking strategy and "
              "prompt output shape before reading anything into this.", file=sys.stderr)
        return 1

    # Scored against CONFIRMED decisions only. NEEDS_POLICY_OWNER rows are unsettled, so neither
    # agreement nor disagreement with them is evidence about the extractor.
    confusion = defaultdict(Counter)
    contested_hits = contested_total = 0
    false_authorisable = []
    scoreable = 0

    for key, predicted in matched.items():
        t = truth[key]
        if t["reviewStatus"] != "CONFIRMED":
            continue
        scoreable += 1
        reviewed = t["reviewedRole"]
        confusion[reviewed][predicted["role"]] += 1

        if key[2] in CONTESTED_PHRASES:
            contested_total += 1
            if predicted["role"] == reviewed:
                contested_hits += 1

        # THE RELEASE CRITERION. A condition is exclusion-ELIGIBLE when the extractor calls it a scope
        # gate AND attaches a machine-decidable documentTypes list — that combination is what a governed
        # authorisation would act on. Eligible where a human said it is not a boundary is a false
        # exclusion waiting for someone to authorise it.
        if (predicted["role"] == EXCLUDING_ROLE
                and predicted["documentTypes"]
                and reviewed != EXCLUDING_ROLE):
            false_authorisable.append((key, reviewed))

    if not scoreable:
        print("\nNo CONFIRMED decisions matched. Nothing to score.", file=sys.stderr)
        return 1

    correct = sum(confusion[r][r] for r in confusion)
    print()
    print(f"AGREEMENT WITH GOVERNED OVERRIDES     {correct}/{scoreable}"
          f"  ({pct(correct, scoreable)})")
    print(f"  (excluded {len(matched) - scoreable} unsettled NEEDS_POLICY_OWNER rows)")
    if contested_total:
        print(f"  on previously contested phrases      {contested_hits}/{contested_total}"
              f"  ({pct(contested_hits, contested_total)})")

    print()
    print("reviewed role -> what the shadow extractor predicted")
    for reviewed in sorted(confusion):
        preds = ", ".join(f"{p}={n}" for p, n in confusion[reviewed].most_common())
        print(f"  {reviewed:26s} {preds}")

    # SCOPE_GATE precision and recall.
    predicted_gates = sum(confusion[r][EXCLUDING_ROLE] for r in confusion)
    true_gates = confusion.get(EXCLUDING_ROLE, Counter())[EXCLUDING_ROLE]
    actual_gates = sum(confusion.get(EXCLUDING_ROLE, Counter()).values())
    false_gates = predicted_gates - true_gates
    missed = actual_gates - true_gates

    print()
    print(f"{EXCLUDING_ROLE} CLASSIFICATION")
    print(f"  predicted                            {predicted_gates}")
    print(f"  genuinely {EXCLUDING_ROLE} (per review)     {actual_gates}")
    print(f"  correct                              {true_gates}")
    print(f"  FALSE {EXCLUDING_ROLE}                     {false_gates}")
    print(f"  missed real gates                    {missed}"
          "   (cost: a redundant comparison each)")
    print(f"  precision                            {pct(true_gates, predicted_gates)}")
    print(f"  recall                               {pct(true_gates, actual_gates)}")

    print()
    print("RELEASE CRITERIA")
    ok = not false_authorisable
    print(f"  false exclusion eligibility          {len(false_authorisable)}"
          f"   {'PASS' if ok else 'FAIL'}   <- must be 0")
    print(f"  false {EXCLUDING_ROLE} count               {false_gates}"
          f"   {'PASS' if false_gates == 0 else 'REVIEW'}   <- ideally 0")
    print("  human overrides overwritten          no     PASS   (this script writes nothing)")
    print("  shadow set used for comparisons      no     PASS   (ValidationStatus.SHADOW)")

    for (key, reviewed) in false_authorisable:
        print(f"    EXCLUSION-ELIGIBLE BUT NOT A GATE: {key[1]} \"{key[2][:60]}\" "
              f"(review said {reviewed})")

    print()
    print("Nothing was written. This is evidence for a decision about the prompt, "
          "not authority to replace a reviewed decision.")
    return 0


def pct(n, d):
    return "n/a" if not d else f"{100.0 * n / d:.0f}%"


if __name__ == "__main__":
    sys.exit(main())
