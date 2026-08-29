# v1.0.0-pilot.3 — configuration manifest

A **patch on `v1.0.0-pilot.1`**, which is unchanged and still a valid frozen build. This release
withdraws a defect introduced after that tag and adds the mechanism that replaces it.

**Supersedes `v1.0.0-pilot.2`, which was never deployed.** That tag holds the same code fix but
omitted two things: the regression test for the fix, and the Maven download retry without which the
images could not be built at all — two release attempts were lost to dropped connections to Maven
Central. Rather than move a pushed tag, `pilot.2` is left frozen and unused. The deployed tag names
exactly the tree that was tested and built.

Feature settings, extraction state, and governance posture are **identical to `pilot.1`** unless
listed below.

## What changed

### Withdrawn: model-reported extraction limitation

`COMPARE-V3` let the comparison model report that the platform's own extraction of a standard was
incomplete. Because the branch was reachable on every requirement, the model used it whenever a
comparison was hard.

| build | evaluated of 191 | validation failures |
|---|---|---|
| `COMPARE-V2` | 187 | 0 |
| `COMPARE-V3` initial | 46 | 145 |
| `COMPARE-V3` recalibrated | 114 | 77 |
| **this release** | **191** | **0** |

Of the 145 failures, 123 were the limitation claimed with no supporting obligation; ~12 named an
obligation a clause did represent. Two prompt revisions did not fix it, because the misfire rate
multiplies across the requirement set and each misfire costs a whole control.

The error was structural rather than verbal:

> The comparison model judges the process against the supplied requirement. It must not also judge
> whether the requirement extractor did its job.

`ComparisonModelOutput` no longer carries `evaluationLimitation` or `unmappedStandardObligations`, so
this cannot regress through prompt wording. A test asserts their absence by reflection.

### Added: governed requirement-integrity gate

| | |
|---|---|
| `RequirementIntegrityStatus` | COMPLETE / INCOMPLETE_ATOMIC_COVERAGE / NEEDS_REVIEW |
| Decision point | ingestion, deterministic — **before any model call** |
| Records applied | **3**, batch `REQ-INTEGRITY-V1`, scoped to `REQSET-V3` |
| Effect | requirement withheld from the model; deterministic `EXTRACTION_REVIEW_REQUIRED` result |
| Conclusion | excluded (`conclusionEligible = false`) |
| Retryable | no — a retry cannot supply a clause the extractor never produced |

The two designs fail in opposite directions. An unsupported model claim cost the entire requirement;
an unsupported integrity record costs nothing, because `blocksComparison()` requires a non-empty
obligation list and the requirement is simply compared normally.

### Only 3 of 11 candidates are real defects

The other 8 are extractor **paraphrases**, not dropped obligations. `CHG-01-R2` is titled "Complete
change record **before** execution" while its source span says "**at authorization**" and its clause
carries `timing: "at authorization"` correctly.

| requirementId | standardVersionId | obligation | type |
|---|---|---|---|
| `CHG-08-R2` | `6a883cacfc6ee17f0bca62ec` | "within one business day" | TIMING |
| `CHG-08-2` | `6a8bdcd6dac91eb0765dc9e9` | "within two business days" | TIMING |
| `CHG-08-3` | `6a8bdcd6dac91eb0765dc9e9` | "within two business days" | TIMING |

So the earlier "11 of 380 (2.9%)" overstated the defect by 73%. A record is written only when the
obligation is quotable from a provenance span.

### Two defects found while verifying

**Coverage ledger key.** Findings were indexed by `requirementId` alone. Ids recur across standards —
**47 of 191** in one three-standard comparison — so the first standard's finding won and the rest were
discarded. `CHG-08-2` was withheld in one standard and `ALIGNED` in another; the ledger reported
`EVALUATED` for both, returning an unassessed requirement to the alignment conclusion. Now keyed on
`(standardVersionId, requirementId)`. Both new tests were confirmed to **fail against the old key**.

**`Finding.conclusionEligible` was a primitive `boolean`.** Every stored finding predates the field,
so Mongo supplied null and construction threw for *all* historical findings — History went blank for
every comparison, not just new ones. Now boxed, with `countsTowardConclusion()` normalising null to
the historically accurate answer (eligible), and a limitation always overriding a stored `true`.

## Retained from the retry work

Retries still cover malformed JSON, missing fields, invalid enums, unknown clause ids, a `PARTIAL`
that omitted an existing unsatisfied clause id, and transient model failures. Only the
extraction-limitation branch and its targeted repair prompt were removed.

## Promoted contract

```text
COMPARE-V2
COMPARE-SCHEMA-V2
```

`REQ-EXTRACT-V5` / V6 remain **shadow only** and are excluded from comparisons.

## Production (GCP) data state at deployment

**This is a code-only compatibility release.** No requirement-set migration, governance-data
transfer, or integrity-record import is included.

| | production (GCP) | local development |
|---|---|---|
| Requirement set | **`REQSET-V1`** — 195 requirements, 5 standard versions | `REQSET-V3`, 380 material |
| Condition overrides | **none** | 118 governed |
| Integrity records | **none at deployment time** | 3 (`REQ-INTEGRITY-V1`, `REQSET-V3`) |
| Coverage ledgers | none | 9 |
| Stored findings | 640 | — |
| Comparison contract | `COMPARE-V2` | `COMPARE-V2` |
| Phase 3 agentic retrieval | disabled | disabled |

An earlier draft of this manifest stated that `REQ-EXTRACT-V3` was the promoted production set. That
is true **locally and not in production**, and the claim has been removed rather than corrected in
place — assuming parity between the two corpora is exactly the error that would make every other
number here untrustworthy.

### Why the code is backward-compatible with this data

| absent in production | behaviour |
|---|---|
| `requirement_integrity_records` | read as zero records; every requirement compared normally |
| `conclusionEligible` on 640 findings | boxed `Boolean` deserializes null, normalised to eligible |
| coverage ledgers | legacy comparisons report "ran before coverage tracking existed" |
| condition overrides | no governed exclusion authorised; requirements stay compared |

The boxed-`Boolean` change is the load-bearing one for production. Deployed as a primitive, it would
have thrown on all 640 stored findings and blanked History for every existing comparison — the exact
failure observed locally.

### Known limitation

Code deployment alone does **not** repair extraction defects in `REQSET-V1`. The three integrity
records are scoped to `REQSET-V3` with local standard version ids, so **none of them apply in
production**. Until a production-specific integrity batch is derived, reviewed and applied, any
`REQSET-V1` requirement with the same off-clause obligation defect can still reach the original
comparison dead end.

Pending, each as a separate governed decision:

1. `import-requirement-integrity.py --set-version REQSET-V1` — **dry run only**, output reviewed and
   hashed into a `REQ-INTEGRITY-PROD-V1` batch before anything is applied.
2. Production condition overrides derived from the production corpus. The 118 local overrides must
   **not** be copied: they are keyed to local standard/version/requirement ids and would either
   attach governance decisions to the wrong requirements or match nothing.
3. `REQSET-V1` → `V3` as a separate corpus release — extracted in parallel as shadow, with a
   requirement-level diff and governance-migration report, promoted only after review and only for
   **new** comparisons. Existing comparisons stay tied to V1.

## Verified before release

| check | result |
|---|---|
| Reactor test suite | **658 tests, 0 failures** |
| UI test suite | 127 tests, 0 failures |
| Deployed prompt verified by rendering from the running jar | `COMPARE-V2`, withdrawn branch absent |
| Model output record has no limitation field | confirmed by reflection |
| End-to-end run `CMP-08b7cfd8-bca` | 191/191 COMPLETED, 0 validation failures |
| Ledger on that run | 189 `EVALUATED` + 2 `EXTRACTION_REVIEW_REQUIRED` |
| Coverage arithmetic | both invariants reconcile |
| Withheld findings | `proc=0`, obligation quoted, excluded from conclusion |
| Status mix | 63 PARTIAL / 61 ALIGNED / 43 MISSING / 22 CONTRADICTED / 2 INSUFFICIENT_EVIDENCE |

Verification was by CONTENT (`javap`, rendering the prompt from the container's jar), not by version
constant. A version constant matching proved nothing on an earlier attempt in this sequence.

## Known limitations

Everything in `pilot.1` still applies, plus:

1. `failedIds` and `skipped` in the coverage ledger are still keyed by bare requirement id. When an
   id recurs across standards, a failure or skip recorded for one marks the same id in all of them.
   The error direction is toward **visibility** — reported as failed when a sibling was, never the
   reverse — unlike the finding key, which erred toward false coverage. Fixing it needs the standard
   version threaded through the applicability coordinator and the job runner's failure set.
2. The 8 paraphrase candidates are compared normally. If any of them *also* dropped an obligation
   from the title, this detector would not find it — the measured 3 is a lower bound.
3. Requirement-integrity records are scoped to `REQSET-V3` and must not carry into a future
   extraction generation, which may have repaired the defect.
4. One local comparison (`CMP-f9b84cbb-d8b`) holds a stale all-`EVALUATED` ledger written before the
   key fix. New runs are correct; no migration was written for a single local row.

## Prerequisite for a future extraction generation

Unchanged from `pilot.1`, plus the invariant this release makes enforceable:

> Every material obligation present in `normalizedRequirement` or in the provenance spans must map to
> at least one atomic clause, or the extracted requirement must be marked `NEEDS_REVIEW` before it
> becomes comparison-eligible.

The three records above are the named regression set for that work. See
`docs/extraction/off-clause-obligation-regression-set.md`.

## Not yet completed for this release

The production-environment gates from `pilot.1` remain **unchecked**: separate GCP project, Secret
Manager with pinned versions, TLS, disk snapshots with a *tested* restore, Ops Agent, uptime checks,
log-redaction verification, smoke comparison in the production project, and a rollback test.
