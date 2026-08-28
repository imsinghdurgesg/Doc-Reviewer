# Shadow evaluation — REQ-EXTRACT-V4

Run 2026-08-28 against `CONDROLE-REVIEW-V1`. **Nothing was promoted, replaced, or written into the
decision path.** The V4 set is stored as `ValidationStatus.SHADOW`, which the comparison selection query
excludes; the live set for this version is still `REQSET-V3`.

## Setup

| | |
|---|---|
| Standard version | `6a8d6f379b065e2acc9ad5e1` (Technology Change Management, v3.0) |
| Shadow set | `REQSET-V4` / `REQ-EXTRACT-V4` |
| Ground truth | 27 reviewed conditions for this version, from the 115-condition batch |
| Requirements extracted | 71 (0 rejected), 55 conditions |

Isolation verified before and after: VALIDATED sets 30 → 30, overrides 115 → 115, Qdrant requirement
vectors 391 → 391, live set `REQSET-V3` → `REQSET-V3`.

## Result

| | V3 (reviewed) | V4 (shadow) |
|---|---|---|
| Predicted `SCOPE_GATE` | 27 | 6 |
| Genuinely `SCOPE_GATE` | 0 | 0 |
| **False `SCOPE_GATE`** | **27** | **6** |
| Missed real gates | 0 | 0 |
| Agreement with reviewer | — | 7/13 (54%) |

Over-classification dropped from 27 false gates to 6 — a **78% reduction** — with no real gate missed.
Precision on `SCOPE_GATE` remains 0% in both, because this standard contains no genuine scope gates at
all: the 2 real ones in the corpus belong to a retention standard. A precision figure computed where the
true positive count is zero cannot exceed 0% however good the extractor is, so **the count of false
gates is the meaningful number here, not the ratio.**

## What V4 still gets wrong

All 6 remaining false gates are the **same phrase**, recurring across 6 requirements:

> "Changes using privileged access or automated production deployment"

One unlearned pattern, not six independent errors. It is the same failure the review documented: a phrase
naming a *class of change* read as a limit on *which documents the standard governs*. A change procedure
that omits privileged-access changes has a gap, not an exemption. V4's added subset-of-work test caught
risk tiers and emergency changes but not access-mechanism classes.

**No V4 scope gate carries a `documentTypes` list**, so none could drive `NOT_APPLICABLE` even without
the governance layer — the same accident of the data that made V3's false-exclusion rate 0. The
governance layer is still what makes that a guarantee rather than luck.

## Caveats — do not over-read this

- **13 scoreable rows.** Small enough that one phrase moves the agreement rate by ~8 points.
- **41 of 55 shadow conditions had no reviewed counterpart** and are unscoreable. V4 extracts different
  and often more granular condition text, so text-keyed matching cannot pair them. They are not counted
  as errors, but it does mean most of V4's output is unmeasured here.
- **One standard.** Extending this to the other nine would need nine more shadow runs.
- The 1 `NEEDS_POLICY_OWNER` row is excluded from scoring: nobody settled it, so neither agreeing nor
  disagreeing with it is evidence about the extractor.

## Recommendation

Do not promote V4 on this evidence. It is a real improvement on the one pattern it was written for, but
13 rows on one standard does not justify replacing 113 reviewed decisions — and it does not need to,
since the override overlay already delivers the correct roles at runtime. The useful next step is a
prompt revision covering access-mechanism and deployment-mechanism classes, then a shadow run across all
ten standards.

## Reproduce

```sh
# extract (never promoted, never indexed)
curl -X POST -H "X-Internal-Service-Token: $INTERNAL_SERVICE_TOKEN" \
  "http://127.0.0.1:8080/internal/shadow-extraction/<standardVersionId>?chunkingStrategyVersion=SEMANTIC-V2"

# score against the reviewed batch
infrastructure/scripts/score-shadow-extraction.py \
  --shadow-set REQSET-V4 --standard-version <standardVersionId>
```
