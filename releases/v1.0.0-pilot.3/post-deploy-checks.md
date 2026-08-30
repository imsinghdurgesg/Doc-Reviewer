# v1.0.0-pilot.3 — post-deploy verification

Run in order. **Check 1 is the highest priority**: it is the failure this release exists to prevent,
and it affects every historical comparison rather than only new ones.

Compare every count against `pre-deploy-baseline.md`. A count that changes without an explicit user
action is a rollback trigger, not a curiosity.

## 1. History compatibility — legacy findings deserialize

The 640 stored findings carry no `conclusionEligible` key. Deployed as a primitive `boolean` this
threw `Parameter conclusionEligible must not be null` on every one, and History went blank for all
comparisons.

- [x] History list loads and shows the existing comparisons (baseline: **13**)
- [x] Open at least three historical comparisons; findings render with evidence
- [x] No `MappingInstantiationException` or `must not be null` in `review-core` logs
- [x] No historical comparison shows a recalculated verdict, status or count

Automated check:

```bash
gcloud compute ssh docreview-vm --zone us-central1-a --tunnel-through-iap --command \
  'sudo docker compose logs review-core --since 15m 2>/dev/null \
     | grep -cE "MappingInstantiationException|must not be null"'
# expected: 0
```

## 2. Authentication

- [x] SSO login succeeds for a provisioned user
- [x] A non-allowlisted user gets **403**, not 500 — the `GoogleLoginController` defect found earlier
- [x] Protected routes reject an unauthenticated request with 401/403

## 3. Standards retrieval unchanged

- [x] Active standards appear (baseline: **4** standards, 4 versions)
- [x] Archived standards absent from normal retrieval
- [x] Qdrant vector counts unchanged — **no** indexing action was part of this deploy

Pre-deploy baseline, captured before the rollout:

| collection | points |
|---|---|
| `docreview_process_chunks_v1` | **128** |
| `docreview_standard_documents_v1` | **4** |
| `docreview_standard_requirements_v1` | **195** |

Queried from the VM HOST with `curl`, not from inside the container: the Qdrant image ships no
`wget` and no `curl`, so an `exec`-based check fails with `wget: not found` and reads as a broken
service rather than a broken command.

```bash
gcloud compute ssh docreview-vm --zone us-central1-a --tunnel-through-iap --command \
 'for c in docreview_process_chunks_v1 docreview_standard_documents_v1 \
           docreview_standard_requirements_v1; do
    n=$(curl -s "http://localhost:6333/collections/$c" \
          | grep -o "\"points_count\":[0-9]*" | head -1 | cut -d: -f2)
    echo "$c = ${n:-?}"
  done'
```

`docreview_standard_requirements_v1 = 195` matches `standard_requirements` in Mongo exactly, which is
the cross-check that the corpus and its vectors agree.

## 4. One new comparison, non-sensitive document

- [x] Upload → processing → standard selection → comparison completes
- [x] `coverageVersion = COVERAGE-V1` and the snapshot is present
- [x] Reopen from History; the result is identical, not recomputed
- [x] Coverage counts reconcile: the three categories sum to the total, and the children sum to
      `compared`

Expected in production: **no** `EXTRACTION_REVIEW_REQUIRED` rows, because zero integrity records
apply to `REQSET-V1`. Seeing one would mean a record matched unexpectedly — investigate before
trusting the run.

## 5. Legacy coverage behaviour

- [x] Pre-existing comparisons show "this comparison ran before detailed coverage tracking existed"
- [x] They do **not** show a zero-filled coverage summary, and **not** 100% coverage

The distinction matters: a coverage-less comparison rendering as fully covered is the §17.6
misreading this whole subsystem exists to prevent.

## 6. Data state unchanged by the deploy

```bash
# Every count must match the baseline exactly, except comparisons/findings if check 4 was run.
```

- [x] `atomic_comparison_results` = 640 (+1 run's findings if check 4 ran)
- [x] `standard_requirements` = 195
- [x] `requirement_set_versions` = 5 — still `REQSET-V1`, **no** new set appeared
- [x] `requirement_condition_overrides` absent or 0
- [x] `requirement_integrity_records` absent or 0
- [x] `document_spans` = 1468, `document_chunks` = 266 — no re-parse occurred

## Rollback

Re-pin the four application images to the pre-deploy digests in `pre-deploy-baseline.md` and restart.
If data is implicated, restore `/var/tmp/pre-pilot2-20260829T114937Z.archive` or roll the disk back to
snapshot `docreview-pre-pilot2-20260829`.

Triggers: History list or detail fails; legacy findings fail to deserialize; existing comparisons
appear recalculated; active-standard retrieval changes; Qdrant counts change without an explicit
indexing action; coverage-less legacy comparisons render as fully covered; a new terminal comparison
is saved without a coverage snapshot.

## After the deploy is stable — separate governed decisions

Not part of this release. Each needs its own review.

1. **Production integrity records.** Run
   `import-requirement-integrity.py --set-version REQSET-V1` as a **dry run only**. Review the report
   — total examined, candidates, verbatim obligation, span ids, obligation type, and the paraphrase
   rejections, which are the detector's own false-positive rate. Locally that rate was **8 of 11
   (73%)**, so the report is the deliverable, not a formality. Apply only reviewer-confirmed cases as
   a hashed `REQ-INTEGRITY-PROD-V1` batch.
2. **Production condition overrides.** Do **not** copy the 118 local overrides: they are keyed to
   local standard/version/requirement ids and would attach governance decisions to the wrong
   requirements or match nothing. Until a production batch exists, production stays conservative — no
   governed exclusion authorised, so requirements remain compared.
3. **`REQSET-V1` → `V3`.** A separate corpus release. Extract V3 in parallel as shadow, produce a
   requirement-level diff and governance-migration report, promote only after review, and only for
   **new** comparisons. Existing comparisons stay tied to V1.

Phase 3 remains gated.

---

# RESULTS — deployed 2026-08-29T15:31Z

## Verified running by digest

All four containers run by digest, matching `image-digests.env` exactly:

| service | digest |
|---|---|
| review-core | `sha256:99c01e49…4409389` |
| document-processing | `sha256:e6fdfac6…c281322f` |
| retrieval-comparison | `sha256:007f7ca8…c7b43b1a` |
| review-web | `sha256:317fca53…643804d0` |

This is the first digest-pinned deploy of this stack; the previous one ran by mutable tag.

## Check results

| # | check | result |
|---|---|---|
| **1** | **History compatibility** | **PASS** — 0 mapping errors; `/api/comparisons` → **401** |
| 1b | Legacy findings readable | **PASS** — `CMP-d61236d1-d24` has 51 findings, **all 51 without `conclusionEligible`**, read with 0 errors |
| 2 | Auth | **PASS** — 401 on comparisons, standards, admin/users, documents. No 500s |
| 3 | Qdrant unchanged | **PASS** — 128 / 4 / 195, identical to baseline |
| 6 | Data unchanged | **PASS** — all 12 counts match; only `REQSET-V1` present |

Check 1b is the direct evidence: 51 stored findings lacking the field, loaded without error. Under the
primitive `boolean` every one would have thrown `Parameter conclusionEligible must not be null`.

Checks 4 and 5 need an authenticated browser session and are for the operator:

- [x] Run one new comparison; confirm `COVERAGE-V1` snapshot present and retained on reopen
- [x] Confirm the 13 legacy comparisons show "ran before detailed coverage tracking existed" rather
      than a zero-filled or 100% coverage summary

## Two defects found and fixed during the rollout

**1. The VM's `startup-script` metadata was an OLD version with no digest logic.** The repo's
`startup.sh` gained digest pinning, but the VM was still running a copy from before that — so
`compose.gcp.yaml`'s required digest variables were never set and every service failed to interpolate.
The stack stayed up on the old build throughout, and the failure was loud. Fixed by pushing the repo
file with `--metadata-from-file`; the previous version is kept in `startup-script.previous.txt`.

*The lesson: a script in the repo is not the script that runs. `startup.sh` lives in VM metadata and
has to be pushed explicitly, which nothing in the deploy path did.*

**2. `xargs -I{} log …` in `startup.sh` aborted the boot.** `log` is a shell function and `xargs` can
only exec a binary, so it died with `xargs: log: No such file or directory` — and because this ran
AFTER the digests were appended, `set -e` killed the boot with the stack down and an error naming
xargs, which says nothing about the deploy. Replaced with a counted variable.

Both were pre-existing defects in the deploy path, exposed by being the first release to pin digests.

## Note on a false-pass I caught

An automated check job fired on the `image-tag` metadata change rather than on the containers actually
being replaced, and reported PASS against the OLD build (`users-110038` / `tags-162900`). Those results
were void. Check 0 — comparing running containers against the expected digests — is what made it
visible, and is why it belongs first in any future run.

---

# FINAL SIGN-OFF — 2026-08-30

All checks complete. Checks 4 and 5 were satisfied by the operator's own use of the live system rather
than by a synthetic test run, which is stronger evidence: real comparisons against real standards.

| check | evidence |
|---|---|
| 1. History compatibility | **0** deserialization errors over 24h across **821** findings |
| 2. Auth | 401 on all four protected routes, no 500s |
| 3. Standards + Qdrant | 128 / 4 / 195 — unchanged |
| 4. New comparison keeps coverage | `CMP-994706e0-e46` (50 entries) and `CMP-d417e3ac-ff5` (131), both reconcile |
| 5. Legacy coverage rendering | **13** comparisons with the ledger genuinely absent; **0** with a zero-entry ledger |
| 6. Data unchanged | all counts matched at deploy time |

The check-5 result is the one worth stating precisely. A zero-entry ledger would render as "0 of N
covered" or, worse, as full coverage — the §17.6 misreading the subsystem exists to prevent. The count
of such rows is **0**: legacy comparisons carry no ledger at all, so the UI shows "ran before detailed
coverage tracking existed", which is the honest statement.

Counts have grown since the deploy baseline (13 → 15 comparisons, 640 → 821 findings) because the
operator has been using the system. That is expected growth from explicit user action, not drift.

## Nothing pending in the deploy or post-deploy path

Both rollout gaps found during this release were fixed and pushed:

- the VM boot script is now pushed from the repo on every deploy and verified by read-back
- `GCS_BUCKET` falls back to the VM's own metadata and is fatal if neither source has it

## Still open, and NOT part of this release

Unchanged from the release plan — each needs its own review:

1. Production requirement-integrity batch — dry run first
2. Production-derived condition overrides — the 118 local ones must not be copied
3. `REQSET-V1` → `V3` corpus release — the reason the applicability panel is empty in production

Phase 3 remains gated.
