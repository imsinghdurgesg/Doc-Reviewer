# v1.0.0-pilot.3 — pre-deploy baseline (GCP)

Captured before the code-only deploy. This is the ROLLBACK TARGET and the reference for the
post-deploy checks: any count below that changes without an explicit user action is a rollback trigger.

Project `uit-uit-et-servicenow-ai-dev`, instance `docreview-vm`, zone `us-central1-a`.

## Image digests before deploy

| container | tag | digest |
|---|---|---|
| review-web | `users-110038` | `sha256:344b6b12ed547c81642118000239c4c328b0043b6ab57f7bdacbcaaf53692eb7` |
| review-core | `users-110038` | `sha256:029f494680aed545f362861bdf28aeba7fa2ee11adf5528b85abc4713096ccc9` |
| retrieval-comparison | `tags-162900` | `sha256:ac4c616b282b937f83b79c18ed8c23148219e7b94b1b5b92ac2c9637a6cc9190` |
| document-processing | `tags-162900` | `sha256:5e3cd0dca1b916dcd7ffaf5839343e8d7bdf88f2a1256f1f58a7c3aa8de72f4f` |
| caddy | `2-alpine` | `sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648` |
| mongodb | `mongo:8` | `sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3` |
| qdrant | `latest` | `sha256:057ee3a8da769fe7310dd3537b4dc7583bf87a95ce8ac43c0af5a46bc580d1fc` |

Application images were already pinned to dated tags rather than `latest`, so rollback is precise.

## Collection counts before deploy

| collection | count |
|---|---|
| `review_core.comparisons` | 13 |
| `review_core.standards` | 4 |
| `review_core.standard_versions` | 4 |
| `review_core.uploaded_documents` | 4 |
| `review_core.users` | 4 |
| `review_core.processing_jobs` | 29 |
| `retrieval_comparison.atomic_comparison_results` | **640** |
| `retrieval_comparison.standard_requirements` | 195 |
| `retrieval_comparison.requirement_set_versions` | 5 |
| `retrieval_comparison.indexing_jobs` | 19 |
| `document_processing.document_spans` | 1468 |
| `document_processing.document_chunks` | 266 |
| `document_processing.document_processing_jobs` | 9 |

Absent by design, and expected to stay absent after this deploy:
`comparison_coverage_ledgers`, `requirement_condition_overrides`, `requirement_integrity_records`.

The 640 findings are the population that the boxed-`Boolean` fix protects.

## Backups

| | |
|---|---|
| Mongo archive | `/var/tmp/pre-pilot2-20260829T114937Z.archive` (815 KB, gzip) |
| Restore verified | `mongorestore --dryRun` parsed the archive: "dry run completed", 0 failed |
| Disk snapshot | `docreview-pre-pilot2-20260829` — **READY**, 100 GB |

The restore was verified as a dry run against the live instance, which proves the archive is readable
and complete. It is NOT a full restore rehearsal into a scratch instance — that gate remains
unchecked in the configuration manifest.

## Startup side-effect audit

Confirmed the deploy cannot silently mutate the corpus:

| mechanism | finding |
|---|---|
| `ApplicationRunner` / `CommandLineRunner` | none present in any service |
| `AppUserSeeder` on `ApplicationReadyEvent` | returns immediately when `users.count() > 0`; production has 4, so inert |
| `auto-index-creation: true` | creates Mongo INDEXES only; no content read, re-extraction or re-embedding |
| automatic re-extraction / reindex | none — indexing and extraction are job-triggered only |

## Rollback

Re-pin the four application images to the digests above and restart. If data is implicated, restore
the Mongo archive or roll the disk back to the snapshot.

Triggers: History list or detail fails; legacy findings fail to deserialize; existing comparisons
appear recalculated; active-standard retrieval changes; Qdrant vector counts change without an
explicit indexing action; coverage-less legacy comparisons render as fully covered; a new terminal
comparison is saved without a coverage snapshot.
