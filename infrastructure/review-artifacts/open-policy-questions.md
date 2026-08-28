# Open applicability questions

Live at **Admin → Applicability**, top of the page. Generated from
`requirement_condition_overrides` where `reviewStatus = NEEDS_POLICY_OWNER`, grouped by condition phrase.

**Nothing in the queue blocks a review.** Every queued condition resolves to `UNKNOWN` at runtime and
cannot drive `NOT_APPLICABLE`, so its requirement is compared and flagged `scope unclear`.

## Current queue: 0 questions

## Resolved

### `"When it is"` — `CHG-14-2` — resolved 2026-08-28 as `CONDITIONAL_OBLIGATION`

Batch `CONDROLE-REVIEW-V2` (sha256 `d9d4a077…e53467`). Three instances confirmed, **none authorised to
exclude**. The two superseded `NEEDS_POLICY_OWNER` rows from `CONDROLE-REVIEW-V1` are `RETIRED`, not
deleted — the audit trail still shows the question was open and how it closed.

**It was not a policy question.** Extraction truncated the clause:

> When the change is a material security or privacy change, a qualified reviewer independent of the
> implementer must assess relevant threat or abuse cases, data exposure, security-control
> effectiveness, logging and monitoring, and rollback readiness.

down to `"When it is"`. The intended condition is *"a material security or privacy change"* — a
materiality trigger, the `CONDITIONAL_OBLIGATION` pattern already confirmed 108 times in
`CONDROLE-REVIEW-V1`. A change procedure that omits security review of material changes has a **gap**,
not an exemption.

Two pieces of corroboration: a third version of the same requirement
(`6a886c9afc6ee17f0bca62f1`) already extracted it as `CONDITIONAL_OBLIGATION`, so the corpus
contradicted itself; and all three now carry one consistent confirmed role.

## The extraction fix

Two changes, because a prompt instruction alone is unverifiable.

**Prompt** (`REQ-EXTRACT-V4`) — rule 11 now requires the condition text to be verbatim **and complete**,
naming the fragment as a counter-example and telling the model to read its own value back and extend it
until it states a testable fact.

**Validator** (`REQ-SCHEMA-V2`) — new rule 11 rejects a condition that is a bare connective, and rejects
any condition citing no source span. A rejected requirement gets one retry with the error, then
`NEEDS_REVIEW` rather than being published (§12.8).

The hard part was what the rule must **not** reject. Real conditions can be two or three words —
`"before signing"`, `"where required"`, `"at authorization"`, `"Low-risk changes"` — so a length
threshold would have failed ten legitimate conditions in the live corpus while still passing a
long-but-empty fragment. The check is a closed list of connectives matched as a **whole value**, never a
prefix: `"when the change is material"` shares a prefix with `"when the"` and must pass.
`ConditionFragmentRejectionTest` pins all ten real short conditions as accepted.

## A latent bug found while applying this

`EffectiveConditionResolver` selected a matching override with `findFirst()` on an unordered Mongo
result. With two active batches matching one condition, **which decision applied depended on document
order** — so a correction imported as a new batch could be silently ignored, showing a stale-but-plausible
role rather than an error. Now `max(by reviewedAt)`, with nulls sorting last. Retiring the superseded
batch is still the intended workflow; this makes the outcome well-defined when someone forgets.

## Still open: condition boundaries

Auditing for truncation found **56 short-fragment conditions** across the V3 sets, and phrases
classified differently in different versions of the same standard:

| phrase | roles assigned |
|---|---|
| `For an emergency` | `SCOPE_GATE` ×5, `CONDITIONAL_OBLIGATION` ×1 |
| `where relevant` | `CONDITIONAL_OBLIGATION` ×1, `IMPLEMENTATION_QUALIFIER` ×2 |

None can exclude anything, so no control is at risk. Rule 11 prevents *new* fragments; it does not
retroactively repair the existing V3 sets, which would need a re-extraction — and that is deliberately
not being done to preserve the reviewed decisions. Same class as the six false gates left in the V4
shadow run ([shadow-eval-v4.md](shadow-eval-v4.md)): condition **boundaries**, not condition **roles**.

## Resolving a future question

```sh
infrastructure/scripts/export-applicability-review.sh > my-review.csv
# set reviewer_role, and reviewer_exclusion only if a scope gate should genuinely exclude
infrastructure/scripts/import-condition-overrides.py my-review.csv --batch MY-BATCH-V3 --apply
# then retire the superseded rows so exactly one active decision remains per condition
```

Freeze the CSV here with its sha256. Decisions apply to the next comparison; completed comparisons keep
the decision that ran, stamped in `applicabilityDecidedBy`.
