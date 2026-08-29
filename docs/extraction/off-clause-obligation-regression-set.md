# Off-clause obligation regression set

**Status:** open. Fixed at the comparison layer by `COMPARE-V3` / `COMPARE-SCHEMA-V3`; the root cause
is an extraction defect and remains **unfixed by design**.

## What these requirements have in common

Each states a material obligation in its **title** that appears in **neither** its `atomicClauses`
**nor** its `normalizedRequirement`. All are `TIMING` or `SEQUENCING`.

The extractor cited multiple source spans and then built the requirement text from only one of them.
For `CHG-08-R2` the provenance lists five spans; the deadline lives in `SPAN-43077829-166-0` (63
chars, verified present), while `normalizedRequirement` is 171 chars — exactly the length of
`SPAN-43077829-168-2`. The span carrying the obligation was cited and ignored.

## Only 3 of the 11 are real

A title-derived candidate is a **hypothesis**, not a finding. Titles are the extractor's paraphrase
and routinely restate an obligation in words the source never uses. Verified against the provenance
spans, **8 of the 11 are paraphrases and only 3 are genuine dropped obligations.**

The clearest case is `CHG-01-R2`, titled *"Complete change record before execution"*:

| | |
|---|---|
| source span | says **"at authorization"**, not "before" |
| `normalizedRequirement` | "For an emergency, the unique record must exist at authorization." |
| clause `timing` | `"at authorization"` — **correct** |

Nothing was dropped. The extractor chose a different word in the title.

**The 3 genuine defects**, each quotable verbatim from a provenance span:

| requirementId | standardVersionId | obligation | type |
|---|---|---|---|
| `CHG-08-R2` | `6a883cacfc6ee17f0bca62ec` | "within one business day" | TIMING |
| `CHG-08-2` | `6a8bdcd6dac91eb0765dc9e9` | "within two business days" | TIMING |
| `CHG-08-3` | `6a8bdcd6dac91eb0765dc9e9` | "within two business days" | TIMING |

So the span check is not a formality — it is what separates a dropped obligation from a paraphrase,
and it cut the set by 73%. `import-requirement-integrity.py` writes a record only when the
obligation is quotable from the source, and prints the paraphrases it rejected so the detector's own
false-positive rate stays visible.

## The original title-derived candidate set (11)

| requirementId | standardVersionId | dropped | title |
|---|---|---|---|
| `4.1` | `6a874bd5f1902d4eafe11e0c` | after | Archive records after active period |
| `CHG-01-2` | `6a886c9afc6ee17f0bca62f1` | before | Complete and classified change record before execution |
| `CHG-01-2` | `6a8bdcd6dac91eb0765dc9e9` | before | Complete change record before execution |
| `CHG-01-3` | `6a886c9afc6ee17f0bca62f1` | before | Complete and classified change record before execution |
| `CHG-01-3` | `6a8bdcd6dac91eb0765dc9e9` | before | Complete change record before execution |
| `CHG-01-R2` | `6a883cacfc6ee17f0bca62ec` | before | Complete change record before execution |
| `CHG-01.b` | `6a87e08e6647ae7688b5e725` | before | Complete change record before execution |
| `CHG-01.i` | `6a87e08e6647ae7688b5e725` | before | Complete change record before execution |
| `CHG-08-2` | `6a8bdcd6dac91eb0765dc9e9` | within | Emergency retrospective review within two business days |
| `CHG-08-3` | `6a8bdcd6dac91eb0765dc9e9` | within | Emergency retrospective review within two business days |
| `CHG-08-R2` | `6a883cacfc6ee17f0bca62ec` | within | Emergency retrospective review within one business day |

`requirementId` is **not unique** across the corpus — IDs recur between standards, so the
`standardVersionId` is part of the key. `CHG-08-R2` says "one business day" while `CHG-08-2/3` say
"two business days" because they come from **different standard versions**, not because either was
fabricated. Both were confirmed against their source spans.

## How the set was derived

Timing/sequencing phrase present in `title`, absent from `atomicClauses` + `normalizedRequirement`,
over `REQSET-V3` where `material: true`. It is therefore a **lower bound**: an obligation dropped
from the title as well as the clauses would not be detected by this query, and non-timing obligation
types are not covered by the phrase list at all.

## What was fixed now (pilot patch)

**A governed integrity record per affected requirement, decided at ingestion.** A requirement with an
evidenced `INCOMPLETE_ATOMIC_COVERAGE` record is withheld from the comparison model entirely — no
unanswerable clause question is asked — and a deterministic `EXTRACTION_REVIEW_REQUIRED` result is
recorded carrying the exact obligation and its source spans. The extracted data is **untouched**.

### A withdrawn approach, and why

The first attempt let the **comparison model** report that our extraction was incomplete
(`COMPARE-V3`). It was withdrawn after measurement:

| | evaluated of 191 |
|---|---|
| `COMPARE-V2` | **187** |
| `COMPARE-V3` initial | 46 |
| `COMPARE-V3` after prompt recalibration | 114 |

Because the branch was reachable on *every* requirement, the model took it whenever a comparison was
hard — 58 responses claimed the limitation while quoting nothing, and ~12 more named an obligation a
clause did represent. Two prompt revisions did not fix it: the misfire rate multiplies across the set
and each misfire costs a whole control.

The error was structural, not verbal:

> The comparison model judges the process against the supplied requirement. It must not also judge
> whether the requirement extractor did its job.

It has no basis for that second judgement — it cannot see the extractor, the provenance spans withheld
from it, or the rest of the set. `ComparisonModelOutput` therefore no longer *has* those fields, so
the regression cannot recur through prompt wording.

The two designs also fail in opposite directions. An unsupported model claim cost the entire
requirement; an unsupported integrity record costs nothing — `blocksComparison()` requires a non-empty
obligation list, so the requirement is simply compared normally.

## What is NOT fixed — option (b), the permanent fix

Deferred to a future governed extraction promotion. **Not** in this pilot patch, and **not** by
editing V3 in place.

Required work for the future extraction generation:

- Build `normalizedRequirement` from **all** materially cited source spans, not one.
- Ensure every material obligation is represented in an `atomicClause`.
- Add timing, sequencing and deadline completeness validation.
- Run in **shadow** first.
- Reapply governed overrides through durable fingerprints (`COND-FP-V1`).
- Promote only after review.

### Proposed extraction invariant

> Every material obligation present in `normalizedRequirement` or in the provenance spans must map to
> at least one atomic clause, or the extracted requirement must be marked `NEEDS_REVIEW` before it
> becomes eligible for comparison.

Note this invariant would have caught all 11 at extraction time, before any comparison ran.

### Constraints that still hold

- Do **not** edit the V3 records in place.
- Do **not** re-extract V3 and overwrite it.
- Do **not** discard the reviewed condition decisions.
- Do **not** apply new extraction behaviour retroactively to historical comparisons.
- `COND-FP-V1` durable condition fingerprints are a **prerequisite** before promoting any new set.

## Using this as a regression set

When a candidate extraction set is evaluated in shadow, every row above must satisfy:

1. The dropped obligation appears in `normalizedRequirement`, **or** in an `atomicClause`, **or** the
   requirement is marked `NEEDS_REVIEW`.
2. No requirement in the set produces a permanent coverage gap.
3. Existing reviewed decisions are not overwritten.

Until then these 11 surface as `Requirement extraction needs review` and are excluded from the
alignment conclusion — which is the honest reading, since the obligation was never assessed.
