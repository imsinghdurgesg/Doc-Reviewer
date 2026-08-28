# Shadow evaluation — REQ-EXTRACT-V5, all ten standards

Run 2026-08-28. **Nothing was promoted, replaced, or written into the decision path.** All ten V5 sets
are stored as `ValidationStatus.SHADOW`, which the comparison selection query excludes.

Isolation verified before and after: VALIDATED sets 30 → 30, overrides 118 → 118, Qdrant requirement
vectors 391 → 391.

## What changed from V4

V4's shadow run left six false scope gates, all one phrase — *"Changes using privileged access or
automated production deployment"*. V4's subset-of-work test named risk tiers and change classes but never
**mechanisms**, so a phrase beginning "using" still read as a boundary.

V5 states a two-part test — the condition must decide whether the requirement applies to the assessment
**at all**, AND the boundary must be evaluable from **confirmed assessment context** — then lists the
categories that are never scope gates: access method, deployment mechanism, operational trigger, event
type, risk-triggered branch, timing clause, implementation mechanism. It also says explicitly that
leading with "for", "when", "if" or "using" does not make a phrase a boundary.

## Results

| metric | V4 (1 standard) | **V5 (10 standards)** |
|---|---|---|
| Reviewed conditions (ground truth) | 116 | 116 |
| Shadow conditions extracted | 55 | **250** |
| Matched to a reviewed decision | 14 | **57** |
| Agreement with governed overrides | 8/14 (57%) | **52/57 (91%)** |
| Agreement on contested phrases | 2/2 | **4/5 (80%)** |
| Predicted `SCOPE_GATE` | 6 | 6 |
| Correct | 0 | **1** |
| **False `SCOPE_GATE`** | **6** | **5** |
| Missed real gates | 0 | **0** |
| `SCOPE_GATE` precision | 0% | **17%** |
| `SCOPE_GATE` recall | n/a | **100%** |
| UNKNOWN rate | 0% | **0%** (1 of 250) |
| Condition-fragment rate | 2% | **0%** |

## Release criteria

| criterion | target | result |
|---|---|---|
| False exclusion eligibility | 0 | **0 — PASS** |
| False `SCOPE_GATE` count | ideally 0 | **5 — REVIEW** |
| All shadow differences visible | yes | **yes** |
| Human overrides overwritten | no | **no** |
| Shadow set used for comparisons | no | **no** |

**Recall is 100% and no real gate was missed**, so the safety direction is right: V5 does not lose
boundaries. Precision is 17% because this corpus contains only one genuine scope gate among the labelled
conditions — a ratio computed against a single true positive is not a stable measure, which is why the
**false-gate count** is the number to read.

**False exclusion eligibility is 0.** None of V5's scope gates carry a machine-decidable `documentTypes`
list, so none could be authorised to exclude even if a reviewer tried.

## What V5 still gets wrong

The mechanism phrase is fixed — *"Changes using privileged access"* no longer appears. The five remaining
false gates are a **different** failure mode:

| phrase | count |
|---|---|
| `Changes to production AI/ML models or automated decision logic` | 3 |
| `All production changes` | 1 |
| `Changes with potential service or user impact` | 1 |

The first is a **change subtype** — a class of change a change-management standard governs, so omitting
the control is a gap, not an exemption. The other two are a universal ("all") and an impact level, both
already named in the prompt; five errors across 250 conditions is closer to residual noise than to a
systematic rule gap.

## Recommendation

**Do not promote V5 yet.** It is a clear improvement — 91% agreement against 57%, fragments eliminated,
the mechanism pattern fixed, no boundary lost — but the release criterion for false `SCOPE_GATE` count is
"ideally 0" and it stands at 5. Two options, both cheap:

1. Add change-subtype wording ("changes to a named system, model, or component class") to the never-a-gate
   list and re-run. The AI/ML phrase accounts for 3 of the 5.
2. Accept 5 and promote, on the grounds that false exclusion eligibility is 0 and no gate can exclude
   without explicit authorisation — the governance overlay makes a false gate cost a redundant
   comparison, not a lost control.

Option 1 is the better order: it is one prompt edit and a re-run, and promoting with a known systematic
error would put that error into the set that governance decisions are then reapplied to.

Ten standards validate behaviour, not scale. Keep V5 in shadow until the result is reviewed.

## Reproduce

```sh
# extract (never promoted, never indexed) — one call per standard version
curl -X POST -H "X-Internal-Service-Token: $INTERNAL_SERVICE_TOKEN" \
  "http://127.0.0.1:8080/internal/shadow-extraction/<standardVersionId>?chunkingStrategyVersion=SEMANTIC-V2"

# score the whole corpus. --by-prompt is REQUIRED for a multi-standard run: the set counter increments
# per standard version, so one run produced nine sets named REQSET-V4 and one REQSET-V5.
infrastructure/scripts/score-shadow-extraction.py --shadow-set REQ-EXTRACT-V5 --by-prompt
```
