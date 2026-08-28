# v1.0.0-pilot.1 — configuration manifest

A **controlled production pilot**, not a general release. The functional workflow is proven end to end;
the remaining work (V5/V6 extraction, durable condition fingerprints, large-corpus retrieval
measurement, Phase 3) is evolutionary and not required for the current capability.

## Feature settings

```yaml
docreview:
  standard-selection:
    mode: REVIEW
    maximum-candidates: 8
    maximum-preselected: 3
    maximum-selected: 5

  agentic-retrieval:
    enabled: false

  shadow-extraction:
    enabled-for-comparison: false

  applicability:
    allow-ungoverned-exclusion: false

  test-auth:
    enabled: false
```

**Automatic standard selection stays disabled.** The reviewer sees and confirms the selected standards
before comparison starts, even though the duplicate standard has been resolved. Selection quality has
not been measured against a representative corpus, so a confirmation step is the honest position.

## Extraction and governance state

| | |
|---|---|
| Requirement set in production | the currently promoted validated set |
| Governed condition overrides | 118 |
| Conditions authorised to exclude | **0** |
| Open policy questions | 0 |
| `REQ-EXTRACT-V5` | SHADOW only — excluded from comparisons by `ValidationStatus.SHADOW` |

**Authorised to exclude: 0** is the load-bearing safety property. A requirement cannot disappear
because the model read a phrase as a scope gate; the platform may report `APPLICABLE` or `UNCERTAIN`,
and `NOT_APPLICABLE` only when a governed exclusion decision permits it. That favours extra review over
silently missing a real obligation.

## Verified before release

- 618 backend tests, 127 UI tests, 0 failures
- Authenticated end-to-end journey (upload → processing → selection → comparison → coverage → History
  reopen), 6/6, against a running stack
- Coverage snapshot persisted, immutable on reopen, and equal to what was recorded at completion
- Terminal comparisons stamped `COVERAGE-V1` cannot be recorded without a coverage snapshot

## Deliberately excluded

```
Phase 3 agentic retrieval
REQ-EXTRACT-V5 shadow sets
Future V6 extraction behaviour
Automatic application of shadow extraction results
Automatic migration of historical requirement sets
Cross-version condition-override carry-forward
Automatic backfilling of coverage for legacy comparisons
Large-corpus retrieval claims
```

## Known limitations

1. Retrieval quality has not been evaluated against thousands of standards.
2. REVIEW mode is the supported pilot workflow.
3. Phase 3 agentic retrieval is disabled.
4. V5 is a shadow extraction set and is not used in comparisons.
5. Comparisons created before `COVERAGE-V1` display "Coverage was not recorded for this comparison."
6. Existing V3 condition fragments are preserved and cannot authorise exclusion.
7. The single-VM deployment is not highly available.
8. A VM or disk failure may cause downtime until recovery.
9. This release is intended for a controlled production pilot.

## Prerequisite for a future extraction generation

`COND-FP-V1` — a durable condition fingerprint
(`standardVersionId + sourceSpanHash + normalizedConditionTextHash + requirementSourceHash`) — is
**required before promoting any new extraction set**, so the 118 governed overrides survive
re-extraction. It is **not** required to operate the current reviewed set, and does not gate this
pilot.

## Not yet completed for this release

The application build is validated; the production environment gates are not. Outstanding from the
go/no-go checklist:

```
[ ] Separate production GCP project and VM
[ ] Secrets moved from .env to Secret Manager, pinned to explicit versions
[ ] Least-privilege service account attached to the VM
[ ] TLS configured and only 80/443 exposed
[ ] Disk snapshot schedule configured
[ ] Backup restoration tested
[ ] Ops Agent installed
[ ] Uptime checks and alerting configured
[ ] Log redaction verified against a running stack
[ ] Production smoke comparison completed
[ ] Rollback procedure tested
```
