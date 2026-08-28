# Merge Plan — Practice Lens UI onto the Platform Backend

Target: the interaction quality of `practice-lens-document-comparison-main` on top of this
platform's evidence, orchestration, and versioning guarantees.

Four workstreams. **A is a hard prerequisite for C** (the HTML renderer needs node anchors that the
API does not currently return). B and D are independent and can run in parallel with either.

| # | Workstream | Depends on | Status |
|---|---|---|---|
| A | Node-anchored evidence API | — | **done** |
| B | GCS as object store + ingestion sync | — | **done** (needs a real bucket to verify) |
| C | HTML-default review workspace, PDF toggle | A | **done** |
| D | Stanford Google SSO | — | **done** (needs real credentials to verify a full login) |
| E | Integration tests (`system-tests/`) | A, B | **scaffolded**; B cases pending |

### Completed in A + E scaffold

Backend **70 tests** (was 66), UI **52 tests** (was 44), `tsc --noEmit` clean, `system-tests`
compiles and correctly stays skipped without `-Dsystem.tests=true`.

- `HydratedEvidence` now carries `parentNodeId`, `charStart`, `charEnd`, `textHash`
  (`ComparisonDtos`, populated in `EvidenceHydrationService`).
- `GET /internal/documents/{id}/node-offsets` on document-processing — node start offsets **derived
  from spans**, not recomputed. The parser's offsets come from locating node text in the raw
  extracted text, and that text is not in the stored canonical artifact; recomputing elsewhere would
  produce offsets that disagree with the spans and land highlights near, not on, the sentence.
- `CanonicalViewService` + `GET /api/{uploads,standards}/{id}/canonical` — joins the canonical
  artifact with those offsets, drops repeated content, sorts by `ordinal`.
- `SpanNodeOffsetParityTest` (4 tests) — pins `node.text.substring(start, end) == span.exactText()`.
  Mutation-checked: a one-character offset drift fails it.
- `resolveHighlight()` + 8 UI tests — returns `null` rather than a plausible guess on every
  unresolvable case, including text-mismatch after a re-parse. Mutation-checked.
- `system-tests`: `SystemTestEnvironment`, `StackReadinessTest` (health + both trust boundaries),
  `EvidenceParityJourneyTest` (cross-service parity, needs a running stack).

### Completed in C

UI **61 tests** (was 52), `tsc --noEmit` clean under `exactOptionalPropertyTypes`, prod webpack
build succeeds.

- `CanonicalHtmlViewer` — renders `CanonicalNode`s (not concatenated text), so `NodeType` drives
  real headings/tables, `repeated` content is already dropped server-side, and page changes draw a
  marker so pagination survives reflow. Zoom scales the type rather than transforming, so text
  reflows instead of magnifying.
- `useViewerShell` — proportional synchronized scroll with a re-entrancy guard, zoom with
  reading-position anchors, fit-width governed by the narrower pane, fullscreen. Renderer-agnostic,
  so it serves both modes.
- `useScrollToFinding` — targets `[data-finding-id]`, which is set **only on a mark actually drawn**.
  A pane with no resolvable highlight stays put rather than jumping somewhere arbitrary.
- `ViewerControlBar` — Text/Original toggle, zoom, fit-width, scroll-together, fullscreen, and a
  mobile single-pane switch. The Original toggle is disabled with an explaining tooltip when neither
  document has a rendition.
- Two spans in one node merge into a single mark (union of ranges) rather than nesting.
- Mutation-tested: removing the merge fails 1 test; degrading the unresolved-highlight guard to a
  whole-node fallback — the reference implementation's behaviour — fails 4.

### C follow-up — the PDF viewer was removed entirely

The Text/Original toggle is gone; the text pane is the only view. Removed: `PdfDocumentViewer`,
`EvidenceHighlightLayer` (and its 16 tests), the pdfjs jest mock, `pdfjs-dist`, the pdfjs split
chunk, and the Terser private-field workaround that existed solely for it.

**Bundle: 2.78 MiB → 700 KB**, and the prod build now compiles with no size warnings.

Kept deliberately:

- **The rendering pipeline** (`LibreOfficePdfRenderer`, `RenderedGeometryEnricher`). It is what
  assigns page numbers to DOCX sources; without it a DOCX finding could cite a clause path but not a
  page, and reviewers cite pages.
- **Parser geometry** (`BoundingBox` on spans). No longer drawn, but it is what lets `SpanBuilder`
  distinguish `EXACT` from `APPROXIMATE`. Dropping it degrades every span to unresolved and removes
  the pane's ability to say how precisely a citation was located.
- **`HighlightQuality`**, re-pointed at offset resolution rather than rectangle precision.
  `worstQuality` now filters on `parentNodeId`/`charStart` instead of `boundingBoxes`, which would
  otherwise have excluded every span and silently reported "no quality information".

No original-file access from the workspace, by decision.

### Completed in B

Backend **80 tests** (was 71). Decisions taken: reconciliation sweep (no Pub/Sub), GCS replaces
MinIO behind the port, `standards/` + `incoming/` prefixes, no original-file access.

- `GcsObjectStorage` — the `ObjectStorage` port's second adapter, selected by
  `docreview.storage.provider`. MinIO keeps `matchIfMissing = true`, so every existing deployment is
  unaffected. The three logical buckets become prefixes inside one real bucket: GCS names are
  globally unique and IAM binds per bucket, so three buckets means three names to reserve and three
  policies to keep aligned.
- **ADC only.** No service-account-JSON option, deliberately — a downloaded private key is the
  credential most likely to reach a ticket, a log, or an image layer. The reference implementation
  accepts pasted JSON; that is a downgrade.
- `StandardsBucketSync` — lists the prefix, ingests what is absent. Stateless and idempotent: it
  holds no cursor, so it cannot lose its place, and a pass that dies halfway does the rest next time.
  One bad object is a skip with a reason, never a failed pass.
- `BucketLocation` — reads `<prefix>/<standard-key>/<version>/<filename>`. The path carries identity
  a bare filename cannot; inferring version from the filename gives a library where every document is
  version 1.0 of itself. Keys are normalised (`Access Control` → `access-control`) so one folder is
  always one standard. Traversal and control characters are stripped — object names are
  attacker-influenceable in a shared bucket and end up in storage keys and Mongo values.
- `StandardsSyncSweep` — fixed delay (cannot overlap itself), first pass off the startup path,
  never propagates so a briefly unreachable bucket does not need a restart.
- `StandardsSyncController` — `GET` returns the last pass (204 when none has run: "nothing ran" and
  "ran and found nothing" are different states), `POST` triggers one synchronously.

**`StandardAdminService.createVersion` was refactored, not duplicated.** The browser upload now
delegates to a byte-array overload that the sync also calls, so the format gate, content-hash
dedupe, version-label collision check, storage write and job dispatch are literally the same code. A
second ingestion path would drift, and the one that drifted would be the automated one nobody
watches.

**Content hash and filename stay separate signals**, as planned: a rename with identical content
returns the existing version and costs no re-embedding.

Verified live: boots under MinIO with the sync endpoint correctly absent (404) and the existing
standards API unaffected (200); boots under `STORAGE_PROVIDER=gcs` and fails with
`docreview.storage.gcs.bucket must be set` rather than a mysterious 404 on first upload. The
traversal and control-character guards are mutation-tested — removing the sanitiser fails 2 tests.

**Not verified: anything requiring a real GCS bucket.** The adapter's read/write/list calls and a
full sync pass have never run against Google Cloud. That needs a project, a bucket and ADC.

### Completed in D

Backend **267 tests** (accurate count — see the note below), 0 failures. Decisions taken: static
env-var allowlist for roles, credentials supplied at deploy time.

- `SsoSecurityConfig` — a second chain under `docreview.security.mode=sso`. Demo mode is the default
  and untouched. The authorization rules deliberately mirror the demo chain line for line: §33's
  point is that role checks are exercised from the start rather than appearing first in production.
- `HostedDomainOidcUserService` — **enforces `hd` on the ID TOKEN CLAIM**, not on the request. The
  `hd` request parameter only preselects Google's account chooser and is dropped by anyone driving
  the flow directly; validating only that is a quiet hole where the login page looks
  domain-restricted while any Google account works. Also requires `email_verified`.
- `SsoProperties` — comma-separated allowlists, `ADMIN` implies `STANDARDS_ADMIN`, every
  authenticated user is a `REVIEWER`. A blank hosted domain **fails startup** rather than admitting
  every Google account on the internet.
- `HostedDomainRequestResolver` — adds the `hd` hint, documented explicitly as a convenience and
  **not** the security boundary.
- Two differences from the demo chain, both forced by the mechanism: **CSRF enabled** (OIDC
  establishes a session cookie the browser sends cross-site; Basic has no ambient credential), and
  **401 rather than a redirect for `/api/**`** (a 302 to Google from `fetch` is an opaque CORS
  failure the SPA cannot act on).
- `CurrentUser.displayName()` now returns the IdP name claim when present, still via the single
  `SecurityContextCurrentUser` seam.

Verified on a live container, both modes on the same image:

| | demo | sso |
|---|---|---|
| boots | yes | yes |
| API unauthenticated | 401 | 401 |
| API with basic auth | 200 | 401 (correctly rejected) |
| browser navigation | — | 302 to Google |
| health public | 200 | 200 |
| GCS sync endpoint | 404 (absent under minio) | — |

The authorization URL carries `hd=stanford.edu`, `scope=openid email profile`, and PKCE
(`code_challenge_method=S256`). Domain enforcement is mutation-tested: weakening the exact match to
a suffix match — which would accept `stanford.edu.evil.com` — fails 3 tests.

**Two bugs found and fixed during verification**, both caught only by running the container:

1. `getSharedObject(ClientRegistrationRepository.class)` returns null while `oauth2Login()` is still
   being configured. The resolver now takes a supplier and builds its delegate on first use.
2. **A regression I introduced.** Declaring the Google registration unconditionally with a blank
   `${GOOGLE_CLIENT_ID:}` crashed **demo mode** at startup — Boot validates every declared
   registration and rejects an empty client id. This was also the true cause of (1). The registration
   now lives in a `spring.config.activate.on-property: docreview.security.mode=sso` document, so it
   exists only in SSO mode.

**SSO is a configurable switch.** `SECURITY_MODE=demo` (the default) reports `ssoEnabled: false`
from `/api/auth/config` and the SPA renders no login screen at all; `sso` requires Google sign-in.
Read at startup, so switching is `edit .env && docker compose up -d review-core` — a runtime toggle
was considered and rejected: it would make authentication mutable on a live system and would void
the startup validation that currently refuses to boot an unconfigured SSO.

**The flow is the GIS popup, not a server-side redirect.** The redirect flow needs a client secret
AND a redirect URI registered per environment; the popup needs only an authorized JavaScript origin.
Probing the real client id confirmed the blocker was `redirect_uri_mismatch`, which PKCE cannot
solve. Verification is equivalent — the ID token is checked server-side against Google's JWKS.

**The audience check is explicit.** Spring's default validators cover signature, issuer and expiry
but NOT `aud`, because the library cannot know which client id is ours. A token minted for a
different Google app is signed by the same keys and names a real stanford.edu user, so without this
it verifies cleanly and authenticates someone who never consented to this application.

**A bug the client had, and how it was fixed.** The SPA first INFERRED the mode from status codes,
but the demo chain rejects `/api/auth/session` with 401 before routing can report the path absent —
so 401 meant "not signed in" under SSO and "there is no sign-in here" under demo. Demo users were
shown a sign-in screen that could never succeed. `/api/auth/config` now declares the answer, is
public in both chains, and the client makes no inference.

Verified on live containers: demo returns `{"ssoEnabled":false}` with basic auth still working; sso
returns the client id and hosted domain, and 401s both `/api/auth/session` and `/api/comparisons`.

**Not verified: a real login.** No Google credentials, so the token exchange, claim validation and
role assignment have never run against Google. Needs a client id, a secret, and
`http://localhost:8080/login/oauth2/code/google` registered as a redirect URI.

### A correction to every test count reported earlier in this work

The `tests="..."` attribute in surefire XML **excludes `@Nested` cases**, and summing it undercounted
every backend total quoted while workstreams A–C were built (e.g. "80 tests" when the real figure was
267). Counting `<testcase` elements instead gives the accurate numbers: review-core 76,
document-processing 97, retrieval-comparison 94. No result changed — the failures were always zero —
but the absolute figures were wrong.

### Bugs found and fixed after the first live run

Found by driving a real Compose stack, none caught by the unit suites:

1. **Node offsets were 7 characters late** on any node whose first sentence fell below
   `MIN_SPAN_CHARS`. The endpoint took the minimum span offset as the node start; a node beginning
   `"Scope. "` has its first citable span seven characters in. Now the endpoint returns an anchor
   (absolute position + text found there) and the client solves
   `nodeStart = charStart - indexOf(text)`. Live parity: 82/84 → **158/158 unique spans**.
2. **Stale refs after a view switch.** React does not null a ref when its element unmounts on a
   conditional branch, so the shell drove detached nodes and scroll silently did nothing. Guarded
   with `isConnected`. (Moot now the toggle is gone, but the guard is still correct.)
3. **Auto-scroll and scroll-sync fought each other.** `scrollTo` on one pane fired a scroll event
   that mirrored its position onto the other, overwriting the target it had just been sent to —
   whichever pane moved second won. Sync is now suppressed while each pane goes to its own evidence.
4. **Panes overflowed the row.** `flex: 1` without `minHeight: 0` means a flex item cannot shrink
   below its content, so the pane's own scroll container never engaged and the top of the document
   was pushed out of reach.
5. **Horizontal overflow.** A filename like
   `OPS-CHG-014_Production_Change_Management_Process_v1.3.docx` is one unbreakable token and forced
   the paper wider than the pane. `overflowWrap: anywhere` on the title and body.
6. **Table content rendered three times.** The DOCX parser emits `TABLE`, `TABLE_ROW` and
   `TABLE_CELL` nodes all covering the same characters. Rows are rendered; the other two are
   suppressed for display only, with cell-citing spans remapped onto the containing row so no
   highlight is lost. The parser is deliberately unchanged — altering what it emits would shift every
   span offset in the corpus and orphan stored findings.

---

## Decisions taken

**HTML is the default review pane; PDF is a toggle.** Both are views over the same span data.
Neither owns the anchoring — `spanId` does.

**GCS replaces MinIO as the object store**, via a new adapter behind the existing `ObjectStorage`
port. MinIO stays as the local-dev implementation of that same port, environment-switched. The
bucket becomes the single source of truth for policy documents.

**Content hash and filename are separate signals**, not one composite. Practice Lens's
`documentId(name, text)` hashes both into one ID, so a rename forces a full re-embed. This platform
already models them separately in `StandardAdminService.createVersion()` and that behaviour is kept.

**Anti-fabrication is non-negotiable.** No fuzzy re-location of model-supplied strings anywhere in
the merged codebase. Practice Lens's `similarityTokens` approach is explicitly not ported.

---

## Workstream A — Node-anchored evidence API

The HTML renderer needs to answer "which node, which characters within it" without searching text.
`EvidenceSpan` already carries `parentNodeId`, `charStart`, `charEnd`, `textHash`. The REST DTO
drops them.

### A1. Extend `HydratedEvidence`

`services/review-core/.../comparison/web/dto/ComparisonDtos.java`

Add to the record: `parentNodeId`, `charStart`, `charEnd`, `textHash`, `ordering`.
Populate in `EvidenceHydrationService.hydrate()` — the fields are already on the span it reads.

Purely additive. Existing PDF consumers are unaffected.

### A2. Serve canonical nodes

The canonical document is already persisted by `CanonicalArtifactWriter` at
`extracted/{versionId}/document.json`. It is not exposed over HTTP.

New read-only endpoint on `DocumentPreviewController`:

```
GET /api/uploads/{documentVersionId}/canonical
GET /api/standards/{standardVersionId}/canonical
```

Returns `CanonicalNode[]`: `nodeId`, `nodeType`, `sectionPath`, `page`, `paragraph`, `ordinal`,
`text`, `textHash`, `repeated`.

Notes:
- Filter `repeated == true` by default (running headers/footers), `?includeRepeated=true` to keep.
- Sort by `ordinal`, never list order — reading order differs from extraction order for tables and
  multi-column layouts.
- Authorization mirrors the existing preview endpoints exactly.
- Do **not** include `boundingBoxes` here; the HTML pane has no use for them and they are the bulk
  of the payload.

### A3. Node-offset resolution contract

Highlighting is a lookup, not a search:

```
node   = nodesById[span.parentNodeId]
start  = span.charStart - node.charStart      // node-relative
end    = span.charEnd   - node.charStart
```

`HighlightQuality` is re-interpreted for HTML mode — the enum is kept, not dropped:

| Value | HTML meaning | Render |
|---|---|---|
| `EXACT` | offsets resolved cleanly inside the node | `<mark>` the exact range |
| `APPROXIMATE` | node found, offsets out of bounds | highlight whole node, label it |
| `PAGE_ONLY` / `UNRESOLVED` | no `parentNodeId` match | show finding, **draw no highlight** |

**`textHash` must be verified before highlighting.** This is HTML mode's equivalent of
`geometryAppliesTo()`. If the node's stored hash does not match the text being rendered, a re-parse
has shifted offsets — suppress to `UNRESOLVED`. This is the only way HTML mode can be silently
wrong, and the contract already provides the tool.

**Acceptance:** a span highlights the same sentence in HTML mode as its bounding box covers in PDF
mode, verified on a fixture with known offsets.

---

## Workstream B — GCS object store and ingestion sync

Practice Lens's `syncGcsStandardsToIndex()` is the reference for *workflow*, not for code. It is a
synchronous full-replace (`replaceSource: 'gcs'` wipes and rebuilds every GCS document per sync,
capped at 200 files, inside one HTTP request). Here it becomes a job.

### B1. `GcsObjectStorage` adapter

Implements the existing `ObjectStorage` port alongside `MinioObjectStorage`. Selected by
`docreview.storage.provider=gcs|minio`. Keeps `StorageBuckets` key layout unchanged — prefixes map
onto object names directly.

Auth: Application Default Credentials preferred; workload identity in deployment. Do not support
pasted service-account JSON (Practice Lens does; it is a downgrade).

### B2. Upload path → bucket is source of truth

`StandardAdminService.createVersion()` already hashes before storing and returns the existing
version on a `contentSha256` match. That stays. What changes is where bytes land and what happens
next:

1. Upload → hash → dedupe check (unchanged).
2. New content → write to GCS under `standardSource(...)`.
3. Object-finalize event → ingestion job → parse, span, chunk, embed, index.
4. Activation still gated on `INDEXED` (§8.1) — unchanged.

Rename with identical content updates metadata only. No re-embed. This is the case worth getting
right; at bucket scale it is the difference between a metadata write and a full re-index against a
shared LiteLLM proxy.

### B3. Pickup: Pub/Sub with a reconciliation sweep

Primary: GCS object-finalize notifications → Pub/Sub → worker endpoint → `GcsIngestionJob`
dispatched through the existing persist-then-dispatch machinery. `JobRecoverySweep` already handles
a worker dying mid-ingest; no new recovery logic.

Fallback: a scheduled reconciliation sweep listing the prefix and enqueuing anything whose
`contentSha256` is absent from Mongo. Notification delivery is at-least-once **and occasionally
not-at-all** — polling alone is wasteful, events alone lose documents.

Idempotency key: `contentSha256`. A duplicate event is a no-op rather than a second ingest.

### B4. Keep from Practice Lens

Its sync reporting contract is good and ports verbatim as the job result:
`discovered / indexed / skipped[{name, reason}] / capped / syncedAt`. Per-file skip reasons and an
explicit `capped` flag mean a partial sync is legible instead of looking complete.

Also port `safeObjectPath()` — it strips `..`, control characters, and backslashes from object
names. Path traversal via object name is a real risk on the download side.

**Open questions for the user:** existing bucket prefix convention (else `standards/` and
`incoming/`); Pub/Sub availability.

---

## Workstream C — HTML-default review workspace

Practice Lens's `ReviewWorkspace` is 1,118 lines of untyped JSX in one file. The **interaction
design** ports; the file does not. Each component lifts into a typed module against the existing
`models/comparison.ts`.

### C1. Interaction shell (renderer-agnostic)

Port from `src/App.jsx`, re-typed:

- `scrollTogether` — synchronized scroll between panes
- zoom via `transform: scale()` with `ResizeObserver` height readback (`DocumentPage`)
- fit-width / fullscreen / mobile single-pane toggle (`mobileDocument`)
- annotation click → both panes navigate
- persisted preferences (`annotationsOpen`)
- `UploadDropzone` with progress
- history filter/sort (`historyFilters.js` → typed)

This shell was written for scaled HTML, which is why it feels stable — it applies unchanged to the
HTML pane and works over the PDF pane too.

### C2. `CanonicalHtmlViewer` (new, default pane)

Renders `CanonicalNode[]` from A2 — **not** concatenated `exactText`.

- `NodeType` drives the element: heading → `<h2>`, paragraph → `<p>`, table → `<table>`.
- Each node carries `data-node-id`.
- Highlight = `<mark>` over the node-relative range from A3.
- Drop `repeated` nodes.
- **Page-break markers in the flow, and `span.page` in the finding chrome.** HTML reflow destroys
  pagination; a reviewer still needs "clause 4.2, page 14" to cite. This is the real cost of HTML
  mode and it is paid explicitly.

Do not reproduce Practice Lens's stylized facsimile — the "INTERNAL PROCEDURE / WORKING COPY"
masthead and hardcoded page-`1` footer. It looks like a document without being the user's document.

### C3. PDF toggle

Existing `PdfDocumentViewer` + `EvidenceHighlightLayer`, unchanged, behind a **"View original"**
toggle. Keeps exact pagination, letterhead, signatures. Still authoritative for the controlled copy.

Both panes read the same `HydratedEvidence`. The toggle is view state only.

### C4. Keep from the platform

- `HighlightQuality` surfacing — Practice Lens has no notion of a failed locate, so it always
  highlights something. Keep the platform's honesty about precision.
- Aligned findings visible (§18.6). Practice Lens's `AnnotationItem` "NO MISMATCH" treatment is the
  nicer execution of a requirement the platform already has — port the presentation.
- Evidence fetched per finding, not shipped with the comparison.

### C5. Explicitly not ported

`similarityTokens` fuzzy matching, model-supplied `currentExcerpt` as the highlight source, and
`normalizeFindings`' unverified quote passthrough.

---

## Workstream D — Stanford Google SSO

`SecurityConfig` is `@ConditionalOnProperty(docreview.security.mode=demo)` and deliberately not the
default. SSO becomes a second chain under `mode=sso`; the demo chain stays for local development.

- OIDC via `spring-boot-starter-oauth2-client`.
- **`hd=stanford.edu` on the authorization request AND re-validated on the returned ID token
  claim.** The request parameter is a UI hint, not enforcement. Validating only the hint is a quiet
  hole.
- Verify `iss`, `aud`, `exp`, and email-verified.

Authorization is untouched — `Role`, `CurrentUser`, `@EnableMethodSecurity` were written to be
exercised regardless of the authentication mechanism.

Role mapping (needs a decision): default every authenticated Stanford identity to `REVIEWER`, with
a Mongo-backed allowlist collection granting `STANDARDS_ADMIN` / `ADMIN`. Alternative is a Google
Workspace group claim.

Also clears the stale `TODO(phase-2)` at `StandardAdminController.java:65` — the actor becomes the
authenticated principal.

**Needed from the user:** client ID (+ secret), and the role-mapping choice.

---

## Workstream E — Integration tests

`system-tests/` currently contains **only `pom.xml`**. Zero end-to-end coverage across three
services, three datastores, and an LLM proxy. Adding GCS sync, an ingestion worker, and SSO on top
of that is where this plan carries real risk.

Practice Lens is genuinely ahead here: its tests parse real PDF bytes and assert documented
per-fixture outcomes against `samples/test-files/EXPECTED_RESULTS.md`.

Port that approach using the existing `demo/` corpus (which already has matched `.txt`/`.pdf`/`.docx`
triples and an `expected/` directory):

1. Upload → parse → span → chunk → index → compare → hydrate evidence, against Testcontainers
   (Mongo, MinIO/GCS emulator, Qdrant) with a WireMock LiteLLM.
2. **Span offsets resolve identically in both HTML and PDF panes** — the A3 acceptance criterion.
3. Content-hash dedupe: re-upload identical bytes → same version, no re-embed.
4. Rename with identical content → metadata update only.
5. GCS sync: skip reasons, cap reporting, idempotent replay of a duplicate event.
6. Activation blocked when not `INDEXED`.

**Do this before the merge lands, not after.**

---

## Sequence

1. **E scaffold + A** — node-anchored API with a test proving HTML/PDF offset parity.
2. **C** — HTML viewer, then the interaction shell, then the PDF toggle.
3. **B** — GCS adapter, then ingestion job, then Pub/Sub with the reconciliation fallback.
4. **D** — SSO once the client ID arrives; independent, can slot anywhere.

A and C are the user-visible payoff and depend on nothing external. B and D both have open
questions outstanding.

---

## Risks

**Empty `system-tests/`** — the largest. Mitigated by sequencing E first.

**HTML pagination loss** — mitigated by page-break markers and page numbers in finding chrome, not
eliminated. Accepted; the PDF toggle is the escape hatch.

**GCS/MinIO drift** — avoided by choosing replacement over mirroring. Revisit only if offline dev
against real GCS semantics becomes a blocker.
