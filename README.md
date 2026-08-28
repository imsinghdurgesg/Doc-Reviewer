# Document-to-Standard Comparison Platform

Implements `docreview-complete-mvp-architecture.md`. Three Spring Boot services plus a React UI.

## Status: Phase 1 (scaffold)

| Phase | State |
|---|---|
| 1 — Reactor and runtime scaffold | complete |
| 2 — Standards administration and versioning | complete, 6 tests passing |
| 3 — Canonical document processing | complete, 13 tests passing |
| 4 — Standard requirement extraction | complete, 29 tests |
| 5 — Embedding and retrieval | complete, 48 tests |
| 6 — Requirement comparison | complete, 69 tests |
| 7 — Review workspace | code complete, npm install needed |
| 8 — History, reproducibility, evaluation | not started |
| 9 — GCP showcase | not started |

**Phase 1** — reactor and six modules, three application classes, 14 shared contracts, service
configuration, Compose infrastructure (Mongo + MinIO + Qdrant all verified healthy), env files.

**Phase 2** — standards administration and immutable versioning:

- `Standard` / `StandardVersion` Mongo documents with §25-compliant Lombok usage
- `ProcessingJob` — durable job record for §21's broker-free orchestration
- Repositories, `ObjectStorage` port, MinIO adapter, `StorageBuckets` key layout
- `StandardAdminService` enforcing three invariants (below)
- REST controller, RFC-7807 error handling, demo security with real authorization
- `StandardVersionTest` covering the comparability rule and structural immutability

**Verified:** the full reactor builds and **19 tests pass** across all six modules.

```
platform-contracts ....... SUCCESS
platform-test-support .... SUCCESS
review-core .............. SUCCESS   6 tests
document-processing ...... SUCCESS  13 tests
retrieval-comparison ..... SUCCESS  69 tests
system-tests ............. SUCCESS   (skipped; -Dsystem.tests=true to run)

BUILD SUCCESS — 88 tests, 0 failures
```

**Phase 4** — requirement extraction:

- `CandidateDetector` — deterministic pre-filter (13 tests)
- `ExtractionPrompt` — protected instructions, per-request nonce delimiter
- `requirement-extraction-v1.json` — protected response schema
- `ExtractionValidator` — §12.8's ten rules (10 tests)
- `LiteLlmRequirementExtractionModelGateway` — the only class touching Spring AI
- `SeverityResolver` — deterministic severity (6 tests)
- `RequirementExtractionService` — orchestration, one retry, rule-10 consolidation
- `StandardRequirementDocument` / `RequirementSetVersionDocument` — versioned persistence
- `EvidenceSpanReader` + HTTP adapter — cross-service span reads
- `RequirementInspectionController` — read-only internal API

**Phase 5** — embedding and retrieval:

- `EmbeddingGateway` + `LiteLlmEmbeddingGateway` — batched, dimension discovered not configured
- `VectorStorePort` + `QdrantVectorStore` — three versioned collections (§13.2)
- `ScoreFusion` — Reciprocal Rank Fusion, versioned (10 tests)
- `RequirementQueryBuilder` — six-input query per §15.2 (5 tests)
- `ContextExpander` — bounded structural context (5 tests)
- `StandardSelectionService` — document-level standard retrieval
- `EvidenceRetrievalService` — requirement-level evidence + coverage recording
- `VectorIndexingService` — writes all three collections with compatibility stamps

### Phase 5 decisions worth knowing

**RRF scores are ordering, not probability.** A first-place result scores 1/61 ≈ 0.0164. Reading that
as "1.6% relevant" and setting a 0.5 threshold rejects everything — a test documents this. Thresholds
are therefore applied to the RAW cosine score, never the fused value.

**Filters are applied server-side, before scoring.** Filtering a returned top-k is the classic
vector-search bug: ten results are requested, the filter removes most, and the caller silently gets
two.

**Point IDs are UUIDs derived from the application ID.** Qdrant accepts only `long` or `UUID` —
verified against the 1.15.0 client, which has no String overload. Deriving the UUID keeps re-indexing
idempotent; a random one would double the search space on every re-index while every result still
looked plausible.

**Embedding dimensions are discovered, not configured.** A configured value that disagrees with the
model surfaces only when Qdrant rejects an insert, with an error naming neither the model nor the
config.

**Coverage is recorded even when retrieval finds nothing.** §15.4 requires it before a MISSING
verdict, because "searched 240 chunks, closest scored 0.31" and "retrieval returned nothing because
parsing failed" produce identical empty lists and opposite correct verdicts.

### A dependency scope trap

The Qdrant client declares gRPC and protobuf at **runtime** scope, but its generated message classes
extend protobuf types and `QdrantGrpcClient` exposes `io.grpc.ManagedChannel`. Compiling against that
API needs them at compile scope, or javac reports `cannot access
com.google.protobuf.GeneratedMessageV3` while the jars sit in the local repository — which reads like
a corrupt cache rather than a scope problem.

**Phase 6** — requirement comparison:

- `ComparisonPrompt` — protected instructions, nonce delimiter, coverage in-prompt
- `comparison-v1.json` — protected response schema, no `severity` field
- `ComparisonOutputValidator` — §18.1–18.3, three layers (13 tests)
- `ClauseAggregator` — deterministic clause→parent roll-up (8 tests)
- `LiteLlmComparisonModelGateway` — comparison alias, separate from extraction
- `RequirementComparisonService` — per-requirement isolation, one retry, §18.7 consolidation
- `Finding`, `AtomicClauseResult`, `ComparisonModelRequest` contracts

### Phase 6 decisions worth knowing

**The parent status is computed, not taken from the model.** The model judges each clause;
`ClauseAggregator` derives the parent. Asking for both invites a parent ALIGNED sitting above a
clause the model just marked CONTRADICTED, and makes the roll-up unauditable. When the computed
status disagrees with the model's own, the finding is flagged `needsHumanReview` — a disagreement is
precisely what a person should look at.

**Any contradicted clause makes the parent CONTRADICTED.** A process that requires approval four ways
and permits self-approval once is not "mostly compliant" — the exception is the finding. Averaging
would bury it.

**MISSING requires adequate coverage.** Validated twice, in `ComparisonOutputValidator` and again in
`ClauseAggregator`. Reporting a search failure as a compliance gap is the most damaging error this
system can make, so `SearchCoverage` travels into the prompt and is re-checked on the way out.

**A clause named in neither the satisfied nor unsatisfied list is INSUFFICIENT_EVIDENCE**, never
assumed satisfied. Assuming the favourable case is how a real gap disappears.

**Per-requirement isolation.** §18.4: three failures out of 200 requirements is a
PARTIALLY_COMPLETED comparison with 197 valid findings — not a failure, and not a success that
quietly omits three.

**Phase 7** — review workspace (`ui/review-web`, TypeScript + Webpack 5 + MUI):

- `package.json` / `tsconfig.json` — strict TS, no Vite/CRA/vitest (§7.5 forbids them)
- `webpack.{common,dev,prod}.js` — ts-loader with `transpileOnly: false`, pdfjs split chunk
- `api/client.ts` — same-origin `/api` only, RFC-7807 error mapping
- `models/comparison.ts` — types mirroring platform-contracts
- `PdfDocumentViewer` — PDF.js, API names verified against pdfjs-dist 5.4.149
- `EvidenceHighlightLayer` — stored bounding boxes, 7 component tests
- `StatusIndicator` — icon per status, not colour alone
- `FindingsPanel` — clause expansion, contradiction-first ordering
- `ComparisonWorkspace` — three panes, both viewers navigate to the selected finding

**Verified:** the highlight arithmetic, run standalone (5/5). **Not verified:** everything needing
`npm install` — typecheck, build, and the Jest suite.

### Phase 7 decisions worth knowing

**Highlight geometry uses `viewport.scale`, read back after rendering** — not the requested zoom.
Those differ once device pixel ratio or fit-to-width is involved, and a highlight computed from the
requested value is subtly misplaced.

**No vertical flip.** Stored boxes are top-down, so `top = y`. Measured: a line at PDF y=451.7 on a
792pt page at scale 0.901 belongs at 407px; a flip puts it at 307px. At mid-page the two are
arithmetically **identical**, which is exactly why such a bug survives review — a standalone test
pins it.

**One rectangle per line, not one per span.** A quote wrapping across three lines is three
rectangles; a single tall block would cover intervening text it does not cite.

**Aligned controls are shown.** §18.6 requires it: a list of three problems gives no sense of how
much was reviewed, and cannot be told apart from a broken run that found three things and stopped.

**A spec deviation, deliberate.** §7.2 says proxy `/api` to port 8081 — that is
`document-processing`, an internal worker the browser must never reach (§3). The dev server proxies
to 8080 (`review-core`) instead; the spec text appears to be an error.

### How this fixes the old extraction design

The previous implementation was regex-only, and it showed: `actor` came out as `"rollback plan"` (a
grammatical subject, not an accountable party) and `condition` was empty on every requirement. This
design makes the regex a *pre-filter* and the model the extractor.

Three specific improvements, each covered by a test:

**Grouping.** A heading and its qualifying sentence are sent together:

```
4.2 Approval
Changes must be approved before deployment.
The approver must be independent of the implementing engineer.
```

Sent separately, the second sentence loses what it qualifies and the first loses the independence
constraint — and no later validation can detect that the result is wrong.

**Prohibition precedence.** `"must not"` contains `"must"`, so prohibition patterns are tested
first. Getting this backwards inverts the requirement and reports compliant processes as violating.

**Non-modal obligations.** `"Approval evidence is retained for seven years"` has no modal verb at
all. Timing and retention cues promote it anyway — a modal-only detector misses that entire class,
which is among the most commonly audited.

### Anti-fabrication, by construction

The model returns span IDs and never quotations. §12.8 rule 2 rejects any ID that was not supplied
as evidence, so an invented citation cannot reach a reviewer. The response schema also has **no
`severity` field**: severity comes from standard metadata and administrator rules (§17.7), so the
model structurally cannot assign it.

### A version pin that was wrong

`json-schema-validator` was pinned to `1.5.10`, which does not exist on Maven Central — that line
has no 1.5.x release. It is now `3.0.6`.

Worth knowing for diagnosis: a failed fetch leaves `.lastUpdated` marker files in
`~/.m2/repository`, so the version directory EXISTS while containing no artifact. Checking for the
directory is therefore not a validity check; look for a `.pom` or a non-sources `.jar`. BOMs
legitimately ship a `.pom` and no jar.

**Phase 3** — canonical document processing:

- 5 canonical contracts (`CanonicalDocument`, `CanonicalNode`, `NodeType`, `SemanticChunk`, …)
- `PositionalTextStripper` — PDF text with per-character positions
- `BoundingBoxBuilder` — character ranges to page rectangles, one per line
- `PdfParser` — nodes, section paths inferred from clause numbering
- `DocxParser` — real heading styles and tables via `getBodyElements()`
- `RepeatedContentDetector` — running headers/footers, excluded from retrieval
- `TextHasher`, `SpanIdGenerator` — evidence integrity and deterministic IDs
- `SpanBuilder` — sentence-level citable spans
- `SemanticChunker` — structure-first chunking with a bounded token fallback

**Verified:** `PositionalTextStripper` and `BoundingBoxBuilder` compile against real PDFBox 3.0.8,
and 12 logic assertions pass when executed standalone (header detection, page-number normalization,
hash sensitivity, span-ID determinism and version scoping).

### Phase 3 decisions worth knowing

**Bounding boxes are TOP-DOWN.** PDFBox's `getYDirAdj()` measures from the page top, so a consumer
places a box with `top = y`, never `top = pageHeight - y`. Applying a bottom-up flip to top-down
coordinates puts every box at `pageHeight - y`; near the middle of a page that reads as a rounding
error rather than an inverted axis, which is how it survives review.

**`TextPosition` has no `getPageNumber()`** — verified against PDFBox 3.0.8, which offers
`getPageWidth`/`getPageHeight` but not the page number. The caller passes the page in.

**The separator overrides in `PositionalTextStripper` are load-bearing.** `writeString` is the only
method receiving positions, so without also overriding `writeLineSeparator`/`writeWordSeparator`
every space and newline is dropped and the extracted text has no word boundaries at all.

**Spans are per SENTENCE, chunks are per SECTION.** Chunks need to be large enough to embed
meaningfully; citations need to be as small as the passage that supports the finding.

### Phase 2 invariants, and why each exists

1. **Content immutability.** A version's bytes never change; a revision is a new version. Without
   this, reopening a comparison cannot resolve what it actually measured (§19.2).
2. **Duplicate detection by content hash.** Re-uploading identical bytes returns the existing
   version. Otherwise every comparison against that standard silently doubles.
3. **Activation requires `INDEXED`.** An ACTIVE version with no extracted requirements produces a
   comparison with no findings — which a reviewer reads as full compliance. This is the most
   dangerous way this system can be wrong, so it is a hard gate rather than a warning.

## Why this is a separate project

It shares no persistence or orchestration with `document-reviewer-project`: Postgres+pgvector →
MongoDB+Qdrant, JobRunr → HTTP dispatch, CRA/JavaScript → Webpack/TypeScript. Those are
replacements rather than refactors, so the older project stays runnable as a reference while this
one is built.

## Prerequisites

- Java 21 (`java -version`)
- Docker
- Node 24
- Network access to the organizational LiteLLM proxy

## First build

```bash
cp .env.example .env      # then fill LITELLM_API_KEY
./mvnw -DskipTests package
docker compose up -d
```

**The first build must have network access.** MongoDB, MinIO, Qdrant, springdoc, Testcontainers and
Lombok are new to this stack and are not in the local Maven cache.

## The design decisions worth knowing

**The model never quotes text.** It returns evidence *span IDs*; the backend validates ownership and
hydrates the exact text and coordinates from storage (§16.4, §16.5). Fabricating a quotation or a
page number is therefore structurally impossible, and nothing has to re-locate a returned string in
the document.

**Atomic clauses are the comparison unit** (§16.1). A compound sentence becomes several
independently testable clauses, so a partial failure cannot hide inside an aligned verdict.

**`LITELLM_BASE_URL` is the proxy ROOT, without `/v1`.** The OpenAI client appends
`/v1/chat/completions` itself. Verified against the configured gateway: root → 200, doubled `/v1` →
404.

**Requirements are extracted once per immutable standard version** (§12.1) and reused, so two users
comparing against the same standard are measured against the same requirement set.

**Severity never comes from the model** (§17.7) — only from standard metadata and administrator
rules, or risk ranking becomes unauditable.

## Layout

```
platform-contracts/      records, enums, evidence and error contracts — no Spring, no Mongo
platform-test-support/   shared fixtures and containers, test scope only
services/review-core/    the only browser-facing service
services/document-processing/   parsing, spans, chunks; no model access
services/retrieval-comparison/  the only service with LiteLLM and Qdrant access
system-tests/            cross-service journeys; -Dsystem.tests=true
ui/review-web/           React + TypeScript + Webpack 5, outside the Maven reactor
```
