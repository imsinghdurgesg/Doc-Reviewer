# v1.0.0-pilot.4 — configuration manifest

A **feature patch on `v1.0.0-pilot.3`**, which remains a valid frozen build. Feature flags, extraction
state and governance posture are unchanged from `pilot.3` unless listed below.

## What changed

### Applicability conditions are now reviewable in the browser

Recording a condition decision previously required editing a spreadsheet and running
`import-condition-overrides.py`. The Admin panel showed the conditions and had no write path, so a
standards admin could see what needed correcting and had no way to correct it.

| | |
|---|---|
| Entry point | **Update applicability** on each INDEXED standard-version row |
| Who | `STANDARDS_ADMIN` and `ADMIN` |
| Scope | one standard version — a condition decision belongs to one extraction of one version |
| Admin → Applicability tab | retained, read-only, as the cross-standard audit view |

### The four exclusion guards are unchanged

Allowing a requirement to be skipped still requires **all** of:

1. the condition classified as limiting which documents the rule covers;
2. skipping explicitly allowed — a separate decision from the classification;
3. the requirement naming specific document types, not "all";
4. a review run with a **Document type** that clearly falls outside that scope.

Miss any one and the review reports an open applicability question rather than skipping silently.

The rule lives in `RequirementConditionOverride.revise()`, so the CSV importer and the UI cannot
diverge and a third caller inherits it.

### Two behaviours worth stating

**Exclusion authority is recomputed on every revision, never carried over.** A condition confirmed as a
scope limit and allowed to skip, later reclassified, loses that permission in the same edit. Carrying it
forward would keep a requirement skipped on the strength of a classification that no longer exists.

**File provenance is cleared on revision.** A CSV-imported row stores the source file name and its
SHA-256. Revising in the browser makes that pairing false — the row would name a spreadsheet it no
longer matches — so those two fields are cleared. Who decided, when, and under which batch are still
recorded.

### Wording

Every label on the dialog was rewritten for a reader who does not know the domain vocabulary. "Scope
gate" became "It limits WHO the rule applies to"; the exclusion control became "Should we stop checking
this requirement for documents outside that scope?", with the effect stated before the button. Stored
values, enum names and the API contract are unchanged.

## Promoted contract

```text
COMPARE-V2
COMPARE-SCHEMA-V2
```

Unchanged from `pilot.3`. No comparison behaviour was touched.

## Production (GCP) data state

Unchanged from `pilot.3`:

| | |
|---|---|
| Requirement set | `REQSET-V1` — 195 requirements, 4 standard versions |
| Condition overrides | none |
| Integrity records | none |
| Phase 3 | disabled |

### The feature will show nothing in production until the corpus is re-extracted

**This matters for anyone testing it.** `REQSET-V1` predates the field that stores applicability
conditions, so all four production standards have **zero** conditions recorded. Opening the dialog will
correctly report that the set states none — the feature is working, there is nothing to review.

The dialog says so explicitly rather than rendering an empty list, because an empty list is
indistinguishable from a standard that genuinely has no conditions.

Populating it is the deferred `REQSET-V1` → `V3` corpus release, unchanged as a separate governed
decision. Verified locally instead, where `REQSET-V3` carries 186 conditions across 6 standard versions.

## Verified before release

| check | result |
|---|---|
| Backend suite | **670 tests, 0 failures** |
| UI suite | 127 tests, 0 failures |
| Exclusion-withdrawal guard | `ConditionReviewRevisionTest` confirmed to fail (2 failures) when the recomputation is replaced by trusting the caller's flag |
| Local deploy | all three jars verified by `javap` to contain the new methods; POST route returns 401 not 404 |
| Reworded strings | confirmed present in the served UI bundle |

## Known limitations

Everything in `pilot.3` still applies, plus:

1. Production has no conditions to review until the corpus is re-extracted (above).
2. The cross-standard summary on the Admin tab counts only the version selected there; there is no
   single figure for "how many requirements can be skipped across every standard".
3. Editing a CSV-imported decision loses the link to its source file, by design.

## Not yet completed

The production-environment gates from `pilot.1` remain unchecked: separate GCP project, Secret Manager
with pinned versions, TLS, disk snapshots with a *tested* restore, Ops Agent, uptime checks,
log-redaction verification, and a rollback test.
