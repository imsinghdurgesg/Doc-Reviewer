# v1.0.0-pilot.3 — post-deploy verification

Run in order. **Check 1 is the highest priority**: it is the failure this release exists to prevent,
and it affects every historical comparison rather than only new ones.

Compare every count against `pre-deploy-baseline.md`. A count that changes without an explicit user
action is a rollback trigger, not a curiosity.

## 1. History compatibility — legacy findings deserialize

The 640 stored findings carry no `conclusionEligible` key. Deployed as a primitive `boolean` this
threw `Parameter conclusionEligible must not be null` on every one, and History went blank for all
comparisons.

- [ ] History list loads and shows the existing comparisons (baseline: **13**)
- [ ] Open at least three historical comparisons; findings render with evidence
- [ ] No `MappingInstantiationException` or `must not be null` in `review-core` logs
- [ ] No historical comparison shows a recalculated verdict, status or count

Automated check:

```bash
gcloud compute ssh docreview-vm --zone us-central1-a --tunnel-through-iap --command \
  'sudo docker compose logs review-core --since 15m 2>/dev/null \
     | grep -cE "MappingInstantiationException|must not be null"'
# expected: 0
```

## 2. Authentication

- [ ] SSO login succeeds for a provisioned user
- [ ] A non-allowlisted user gets **403**, not 500 — the `GoogleLoginController` defect found earlier
- [ ] Protected routes reject an unauthenticated request with 401/403

## 3. Standards retrieval unchanged

- [ ] Active standards appear (baseline: **4** standards, 4 versions)
- [ ] Archived standards absent from normal retrieval
- [ ] Qdrant vector counts unchanged — **no** indexing action was part of this deploy

```bash
gcloud compute ssh docreview-vm --zone us-central1-a --tunnel-through-iap --command \
  'sudo docker exec -i docreview-qdrant-1 sh -c "wget -qO- http://localhost:6333/collections" 2>/dev/null'
```

## 4. One new comparison, non-sensitive document

- [ ] Upload → processing → standard selection → comparison completes
- [ ] `coverageVersion = COVERAGE-V1` and the snapshot is present
- [ ] Reopen from History; the result is identical, not recomputed
- [ ] Coverage counts reconcile: the three categories sum to the total, and the children sum to
      `compared`

Expected in production: **no** `EXTRACTION_REVIEW_REQUIRED` rows, because zero integrity records
apply to `REQSET-V1`. Seeing one would mean a record matched unexpectedly — investigate before
trusting the run.

## 5. Legacy coverage behaviour

- [ ] Pre-existing comparisons show "this comparison ran before detailed coverage tracking existed"
- [ ] They do **not** show a zero-filled coverage summary, and **not** 100% coverage

The distinction matters: a coverage-less comparison rendering as fully covered is the §17.6
misreading this whole subsystem exists to prevent.

## 6. Data state unchanged by the deploy

```bash
# Every count must match the baseline exactly, except comparisons/findings if check 4 was run.
```

- [ ] `atomic_comparison_results` = 640 (+1 run's findings if check 4 ran)
- [ ] `standard_requirements` = 195
- [ ] `requirement_set_versions` = 5 — still `REQSET-V1`, **no** new set appeared
- [ ] `requirement_condition_overrides` absent or 0
- [ ] `requirement_integrity_records` absent or 0
- [ ] `document_spans` = 1468, `document_chunks` = 266 — no re-parse occurred

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
