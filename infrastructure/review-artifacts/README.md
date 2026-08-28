# Review artifacts

Frozen, hashed inputs to governed decisions. **These files are evidence, not configuration.** Nothing
reads them at runtime: each was imported once into MongoDB, which is the live source of truth. Keeping a
CSV in the query path would make a spreadsheet edit change a compliance decision with no audit trail.

Files here are mode `444` on purpose. To supersede a batch, add a new file and a new batch id — never
edit one in place, or the hash stored on the imported rows stops matching what a reader can inspect.

## CONDROLE-REVIEW-V1

`condrole-review-v1.csv` — sha256 in `condrole-review-v1.csv.sha256`

Human review of every condition the REQ-EXTRACT-V3 extraction classified as a scope gate, because only a
`SCOPE_GATE` can make a requirement `NOT_APPLICABLE` and a wrong one silently removes a real control from
a review.

| | |
|---|---|
| Rows in file | 240 conditions (all roles) |
| Reviewed | 115 — every predicted `SCOPE_GATE` |
| Reclassified | 113 |
| Upheld as `SCOPE_GATE` | 2 |
| **Extractor precision on `SCOPE_GATE`** | **2%** |
| **False `NOT_APPLICABLE` rate** | **0** |

Imported as batch `CONDROLE-REVIEW-V1`: 113 `CONFIRMED`, 2 `NEEDS_POLICY_OWNER`, **0 authorised to
exclude**.

### Why the false-exclusion rate was already zero

Not because the classifier was safe — because none of the 115 conditions carried a `documentTypes` list,
so no exclusion was ever machine-decidable. The zero came from the data, not the design. `SCOPE_GATE`
now additionally requires an authorising override before it can exclude, so the property holds by
construction rather than by luck.

### What the review found

108 of the 113 reclassifications became `CONDITIONAL_OBLIGATION`. The recurring extractor error was
reading a phrase that names *a subset of work* ("emergency changes", "for medium- and high-risk
changes", "changes affecting persistent data") as a limit on *which documents the standard governs*. A
change-management procedure that omits emergency-change handling has a **gap**, not an exemption — so
treating those as scope gates would have hidden real findings behind a clean report.

The 2 upheld gates are genuine material limits: "for records containing personal data" and "containing
personal data" restrict which records a retention control governs. Neither is authorised to exclude,
because neither carries a machine-decidable `documentTypes` list — a prose gate cannot be evaluated
deterministically, so authorising it would licence an exclusion nothing can check.

### The 2 unresolved rows

Both are the condition text `"When it is"` — a truncated extraction fragment with no evaluable content.
They are `NEEDS_POLICY_OWNER` / `UNKNOWN` / cannot exclude rather than being guessed at. The requirements
are still compared and flagged; a reviewer sees an open question instead of a false certainty.

## Round trip

```sh
# export a fresh review file (reviewer fills reviewer_role, reviewer_exclusion, reviewer_notes)
infrastructure/scripts/export-applicability-review.sh > applicability-review.csv

# dry run: prints the batch and a summary, writes nothing
infrastructure/scripts/import-condition-overrides.py <csv> --batch <BATCH-ID>

# apply
infrastructure/scripts/import-condition-overrides.py <csv> --batch <BATCH-ID> --apply
```

The importer refuses a batch outright if any row is malformed, rather than applying it partially — a
half-imported review is harder to reason about later than one that was rejected.
