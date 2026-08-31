# Document review platform — user and administrator guide

Applies to **v1.0.0-pilot.3**.

This guide covers both audiences. Part 1–4 are for anyone reviewing a document; Part 5–9 are for
administrators. Part 10 explains the vocabulary the tool uses, which is worth reading once even if you
only review documents — several words mean something narrower here than in ordinary speech.

---

## What this tool does, and what it does not

It compares **one uploaded process document** against the **requirements of standards you select**, one
requirement at a time, and reports for each requirement whether the document satisfies it — with a link
to the exact passage that supports the answer.

What it does **not** do:

- It does not give your document a score, a grade, or a pass/fail. Compliance is a judgement about
  specific requirements, and a single number would hide which ones.
- It does not decide whether a requirement applies to your situation. It flags that question for a
  person when the answer is unclear.
- It does not read anything you have not given it. If a requirement is addressed in another document,
  the tool reports it as absent from *this* one — which is accurate, and why the coverage panel exists.

**Every finding is checkable.** A verdict always cites the passage it rests on, in both the standard and
your document. If you disagree with a finding, you can see precisely what the tool looked at.

---

## Part 1 — Signing in and what you can see

Sign in with your organisational Google account. There are three roles:

| role | can do |
|---|---|
| **Reviewer** | Upload documents, run reviews, read their own results |
| **Standards admin** | All of the above, plus manage the standards library |
| **Admin** | All of the above, plus users, AI settings, cloud sync, and governance |

The roles nest: an **admin** is automatically a standards admin, and both are reviewers. Curating the
library and deciding who may sign in are separate powers, though — a standards admin cannot manage
users.

If your account is not on the allowlist you get a clear "access denied" message, not an error page. Ask
an administrator to add you.

<!-- SCREENSHOT: docs/images/roles-denied.png
     The access-denied screen a non-allowlisted user sees. The point is that it is a plain explanation,
     not a stack trace or a blank page — capture the whole viewport so that reads clearly. -->
![Access denied for a user not on the allowlist](docs/images/roles-denied.png)

**You see your own reviews.** History is scoped to the account that ran each review. An administrator
does not silently see everyone's documents.

<!-- SCREENSHOT: docs/images/history-reviewer-column.png
     The History list showing the reviewer column, which is what makes the scoping visible. Blur real
     names and email addresses. -->
![History, scoped to the signed-in reviewer](docs/images/history-reviewer-column.png)

---

## Part 2 — Getting a standard into the system

A review needs at least one standard indexed first. Two ways in, both requiring the **standards admin**
role.

### Option A — Upload in the browser

**Standards** → add a standard → upload a version. Give it a title, category, and tags; these become the
chips you filter on later.

<!-- SCREENSHOT: docs/images/admin-new-standard.png
     The new-standard form with title, category and tags filled in. Capture BEFORE upload so the reader
     sees what they must supply, not the result. -->
![Creating a standard](docs/images/admin-new-standard.png)

<!-- SCREENSHOT: docs/images/derived-metadata-chips.png
     The standards list showing category and tag chips on a saved standard — this is what the metadata
     is FOR, and the connection is not obvious from the form alone. -->
![Category and tag chips in the library](docs/images/derived-metadata-chips.png)

### Option B — Drop it in the cloud bucket

Files placed in the configured Google Cloud Storage bucket are picked up by a sweep that runs every five
minutes. Useful for bulk loading.

<!-- SCREENSHOT: docs/images/bucket-layout.png
     The bucket in the GCS console showing where files go. Blur the project/bucket name if it identifies
     the organisation. -->
![Bucket layout for drop-in uploads](docs/images/bucket-layout.png)

### Watching it index

Indexing extracts the standard's requirements, splits each into atomic clauses, and builds the search
vectors. A large standard takes a few minutes.

The status progresses through parsing → spans → chunks → embeddings → requirement extraction. If it
stalls, see troubleshooting in Part 9.

<!-- SCREENSHOT: docs/images/indexing-progress.png
     Indexing mid-run, with the stage visible. A finished one shows nothing about the stages, which is
     the thing a waiting reader wants to recognise. -->
![Indexing in progress](docs/images/indexing-progress.png)

> **Do not upload the document you want reviewed as a standard.** The standards library is the
> authoritative corpus every review is measured against. A process document indexed there would start
> appearing as a source of requirements for everyone.

---

## Part 3 — Reviewing a document

### Step 1 — Upload it

**Home** → upload. PDF, DOCX, and CSV are supported. The file is parsed, rendered for the side-by-side
viewer, and split into passages.

A document is only reviewable once parsing has produced passages. If it has none — an image-only scan,
for example — the tool says so rather than reviewing it and reporting everything as missing.

<!-- SCREENSHOT: docs/images/upload-document.png
     The upload panel with one document uploaded and ready. If you can also capture a not-yet-reviewable
     document alongside, that contrast is worth more than either alone. -->
![Uploading a document for review](docs/images/upload-document.png)

### Step 2 — Give it context (the step most worth doing)

After upload, the tool reads your document and tells you what it thinks it is — shown as chips under
**"This looks like:"**. Those are a guess, and you can correct them.

<!-- SCREENSHOT: docs/images/context-inferred-chips.png
     The "This looks like:" chips right after upload, BEFORE the reviewer edits anything, with the
     "Use these" action visible. The guess-then-correct flow is the thing to capture; a screenshot of
     the filled form does not show that the tool proposed anything. -->
![Inferred document context](docs/images/context-inferred-chips.png)

Four fields, all optional:

| field | what it means | example |
|---|---|---|
| **Department** | Who owns this process | `IT Operations`, `Finance` |
| **Standard family** | The body of rules you care about | `Change management`, `Access control` |
| **Document type** | **What kind of document this is** | `Change management procedure`, `Vendor contract` |
| **Assessment details** | Anything else in plain English | "This covers emergency changes only" |

<!-- SCREENSHOT: docs/images/context-fields.png
     The four fields filled in, with the recommended-standards list beside them so the reader can see
     that editing the context changes the recommendations. -->
![Setting assessment context](docs/images/context-fields.png)

**Why bother?** Two distinct payoffs, and the second one is the one people miss.

**It finds the right standard.** The context feeds standard recommendation directly. A document
described as a *"Change management procedure"* in *"IT Operations"* matches a change-management
standard far better than the same file with no description. Without context the tool has only the
document's own wording to go on.

**It unlocks the applicability decision — and this is the important one.**

Many requirements are conditional: *"For vendor contracts, a right-to-audit clause must be
included."* To decide whether that applies to your document, the tool must know **what kind of
document it is**.

That is exactly what **Document type** supplies. And here is the consequence:

| Document type | what happens to a scoped requirement |
|---|---|
| **Left blank** | The tool cannot tell → **"Applicability review required"**. A person must decide |
| **Filled in** | The tool compares it against the requirement's stated scope and can resolve it automatically |

So leaving Document type blank does not make the tool assume the requirement applies — it makes it
*ask you*. Every scoped requirement becomes a manual decision. Filling in one field can clear a whole
column of open questions.

> **This is the single highest-value field on the page.** It is optional, so it is easy to skip, and
> skipping it is why an applicability column fills up with questions you then have to answer by hand.

### Step 3 — Choose standards

Select one or more standards from the library. You can filter by category and tag.

**If you select none**, the tool picks the single best-matching standard for you and tells you which, with
its score. One, not several: selecting three standards that all derive from a common framework produces
the same control three times, and you cannot tell whether that is three problems or one.

The runner-up is still reported, with its score, so a close call stays visible and you can select it
explicitly.

<!-- SCREENSHOT: docs/images/select-standard.png
     Standard selection with the category/tag filter in use. Ideally capture an auto-selection result
     showing the chosen standard's score AND the reported runner-up, since that pairing is what the
     surrounding text explains. -->
![Choosing standards](docs/images/select-standard.png)

**On size:** a review spanning more requirements than the configured limit (500 by default) is **rejected
up front**, telling you the count. It is not silently truncated — a review that quietly dropped a third of
its requirements while reporting "completed" is worse than one that refuses.

### Step 4 — Wait

Each requirement is retrieved and evaluated separately. Progress shows which stage it is in and how many
requirements are done.

A few hundred requirements takes several minutes. You can leave the page; the review continues.

<!-- SCREENSHOT: docs/images/comparison-progress.png
     A review mid-run with the stage and the evaluated/total counter visible. -->
![Comparison in progress](docs/images/comparison-progress.png)

### Step 5 — Read the findings

Each finding is one requirement, with:

| | |
|---|---|
| **Status** | the verdict — see Part 10 |
| **Requirement** | what the standard demands, and the clauses it breaks into |
| **Evidence** | the passages in *your* document the verdict rests on |
| **Reason** | why the tool reached that verdict |
| **Recommended action** | what would close the gap, where applicable |

**Aligned findings are kept, not hidden.** A list showing only three problems tells you nothing about how
much was actually checked.

<!-- SCREENSHOT: docs/images/findings-list.png
     The findings panel with a MIX of verdicts, including at least one PARTIAL so its named unsatisfied
     clause is visible, and at least one ALIGNED so the "aligned findings are kept" point is evident. -->
![Findings list](docs/images/findings-list.png)

### Step 6 — Side-by-side view

Click a finding to see the standard's requirement beside your document, with the cited passages
highlighted. This is where you verify the tool rather than trust it.

<!-- SCREENSHOT: docs/images/side-by-side.png
     Requirement on one side, document on the other, with a cited passage highlighted. This image
     necessarily shows document text — use a document you are willing to publish. -->
![Side-by-side verification](docs/images/side-by-side.png)

### Step 7 — Read the coverage panel

**This is the most important panel and the easiest to skip.** It answers: *was every requirement
actually tested?*

A findings list cannot answer that. A requirement that was never evaluated leaves no trace in a list of
findings, and its absence looks exactly like compliance.

The panel breaks the total down:

```
Total requirements                          191
  Compared                                  191
    Applicability confirmed                 189
    Applicability review required             0
    Extraction review required                2
  Not applicable                              0
  Not evaluated                                0
```

The three second-level categories — compared, not applicable, not evaluated — account for every
requirement exactly once. The indented lines are subsets of "compared", not separate outcomes; the
layout is deliberate, because four sibling numbers invite you to add them and reach a total larger than
the whole.

If the numbers ever do not add up, the panel says so rather than showing figures you would reasonably
trust.

<!-- SCREENSHOT: docs/images/coverage-panel.png
     The coverage panel EXPANDED, showing the indented subsets beneath "Compared". A collapsed panel
     misses the entire point of the layout. If you have a review with a non-zero "Extraction review
     required" or "Applicability review required" line, use that one. -->
![Coverage panel](docs/images/coverage-panel.png)

---

## Part 4 — Reading an incomplete review

Not every review finishes cleanly, and the tool distinguishes the reasons. They are not
interchangeable, and the difference tells you what to do next.

| what you see | what it means | what to do |
|---|---|---|
| **Not evaluated** | Never attempted, or failed. No verdict exists. | Use **Retry** on the requirement, or **Rerun** the review |
| **Not applicable** | A governed decision excluded it | Nothing — it is a decision, not a failure |
| **Applicability review required** | Compared, but whether the requirement governs your document is unresolved | A person confirms scope before acting on it |
| **Extraction review required** | Compared, but *our* extraction of the standard was incomplete | Report it — retrying cannot fix it |

### "Extraction review required" — what this actually means

This is the one that needs explaining, because it is a defect in the platform, **not in your document**.

It means: the standard states an obligation — typically a deadline like *"within two business days"* —
that our extraction of that standard failed to capture as a checkable clause. The tool can see the
obligation in the source text but has no clause to evaluate it against.

So it declines to answer, shows you the exact obligation it could not assess, and **excludes the
requirement from the overall conclusion**. It does not guess, and it does not report compliance with
something it never checked.

There is no Retry button on these, deliberately. The missing clause comes from extraction, upstream of
the comparison, so another attempt would reach the same wall. Report it to an administrator; the fix is
a corrected extraction of the standard.

<!-- SCREENSHOT: docs/images/extraction-review-entry.png
     A coverage entry expanded on "Requirement extraction needs review", showing the quoted obligation
     and the explanation that re-evaluating will not help. Note the deliberate ABSENCE of a Retry
     button — that absence is the point, so frame it so a reader can see no button is offered. -->
![Extraction review required](docs/images/extraction-review-entry.png)

### Retry and Rerun

- **Retry** re-evaluates a single failed requirement in place. The amended finding is stamped with the
  prompt and model versions it ran under, so a reader can see it was produced separately.
- **Rerun** creates a new review with the same document and standards, leaving the original intact.

<!-- SCREENSHOT: docs/images/partial-review-notice.png
     A partially completed review: the top notice naming the shortfall, with the coverage entries and a
     Retry button on a genuinely failed requirement. -->
![Partial review with retry available](docs/images/partial-review-notice.png)

---

## Part 5 — Administrator setup, in order

Nothing works until step 1. The order matters.

### 1. AI and matching — **required**

**Admin → AI & matching**: the model provider, server URL, API key, and model names.

Comparisons fail with a clear message until this is set. That is deliberate — a tool that silently fell
back to some default provider would send your documents somewhere you did not choose.

The key is **encrypted at rest** and never returned to the browser; the UI only shows whether one is set.

<!-- SCREENSHOT: docs/images/admin-ai-matching.png
     The AI & matching tab: provider, server URL, key state, model aliases, and the three instruction
     boxes. MASK the key field — the app never displays a stored key, but a screenshot taken mid-entry
     would. -->
![AI and matching settings](docs/images/admin-ai-matching.png)

There are also three optional **instruction boxes** to steer how the AI judges compliance, splits
requirements, and describes standards. They are *appended* to the built-in rules, which always win — an
administrator with a text box cannot remove the rules that make a finding citable.

> **Changing the provider takes effect immediately for comparisons, but embeddings need the retrieval
> service restarted** before they follow. Until then, indexing and comparison could be talking to
> different providers.

### 2. Users

**Admin → Users**: the allowlist and role assignment. Someone not listed cannot sign in.

At least one administrator must exist or nobody can manage users; the system logs loudly at startup if
the allowlist has no admin.

<!-- SCREENSHOT: docs/images/admin-users.png
     The users panel with a mix of roles assigned, so the nesting described above is visible. Blur real
     email addresses. -->
![Users and roles](docs/images/admin-users.png)

### 3. Standards library

**Admin → Standards library**: add, upload, activate, archive.

**Archive rather than delete** a superseded version. Reviews that already ran against it keep pointing at
it, and deleting it would leave those results citing a standard that no longer exists. An archived
version stops appearing in normal retrieval but remains readable for historical reviews.

<!-- SCREENSHOT: docs/images/admin-standards-library.png
     The library with at least one ARCHIVED version visible alongside active ones, since the archive-vs-
     delete distinction is what the text argues for. -->
![Standards library](docs/images/admin-standards-library.png)

### 4. Cloud sync — optional

**Admin → Cloud sync**: the bucket and the on/off switch for the five-minute sweep.

### 5. Prompts — read-only

**Admin → Prompts**: exactly what the AI is told, with version identifiers.

Deliberately not editable. These carry the rules that make findings checkable — that a verdict must cite
evidence that exists, that "partially satisfied" must name which clause falls short. Editing them freely
would produce responses that fail validation rather than merely read differently.

<!-- SCREENSHOT: docs/images/admin-prompts.png
     The Prompts tab with one prompt expanded and its version chip visible. The version is the part that
     matters for auditability, so do not crop it out. -->
![Prompts, read-only](docs/images/admin-prompts.png)

---

## Part 6 — Condition governance

**Admin → Condition governance.** This is the part of the tool that decides whether a requirement
*applies*, and it is deliberately conservative.

### The problem it solves

Many requirements are conditional: *"For changes affecting production systems, a rollback plan must be
documented."* Whether that requirement applies to your document depends on what the condition means.

Some conditions genuinely limit scope — if a standard covers only cloud systems, it does not apply to an
on-premise process. Others merely describe *when* an obligation triggers, and treating those as
scope limits would silently excuse real requirements.

### How it stays safe

Two separate decisions, deliberately:

1. **What kind of condition is this?** Only a genuine scope gate can ever exclude a requirement.
2. **Is exclusion authorised?** A separate, explicit permission.

Both must be true before a requirement is marked not applicable. The default is that a requirement
**stays compared** — the tool would rather show you a requirement you decide is irrelevant than hide one
that mattered.

Reviewed decisions are stored as an **overlay**, not by rewriting the extraction. Reopening an old review
shows the decision that was in force when it ran, not today's.

<!-- SCREENSHOT: docs/images/admin-condition-governance.png
     The condition governance panel showing extracted role, reviewed role, and provenance on the same
     row. If a row exists where the reviewer DISAGREED with the extractor, use it — that disagreement is
     the whole reason the panel exists. -->
![Condition governance](docs/images/admin-condition-governance.png)

### Policy owner queue

**Admin → Policy owner queue**: conditions nobody has resolved yet, grouped so the same open question
across several standards appears once rather than as several small problems.

<!-- SCREENSHOT: docs/images/admin-policy-owner-queue.png
     The queue with at least one grouped entry, so the grouping across standards is visible rather than
     just asserted. -->
![Policy owner queue](docs/images/admin-policy-owner-queue.png)

---

### Walkthrough — reviewing conditions on a newly uploaded standard

The end-to-end procedure. Steps 1–4 are read-only and safe; step 5 writes, and step 6 is the only one
that can remove a requirement from a review.

#### Step 1 — Upload and index the standard

Part 2. Wait for requirement extraction to finish. Conditions are extracted alongside requirements, so
there is nothing to review until indexing completes.

#### Step 2 — Open the condition list

**Admin → Condition governance** → select the standard version. Each row is one condition found on one
requirement:

| column | meaning |
|---|---|
| Requirement | which requirement states the condition |
| Condition text | the wording, verbatim from the standard |
| **Extracted role** | what the model classified it as |
| **Reviewed role** | what a human confirmed — blank until reviewed |
| Provenance | which review batch decided it, if any |

<!-- SCREENSHOT: docs/images/condition-list-unreviewed.png
     The condition list for a freshly indexed standard: extracted roles populated, reviewed roles all
     blank. This "before" state is what makes the review work visible; a fully-reviewed list does not
     show that anything needed doing. -->
![Conditions awaiting review](docs/images/condition-list-unreviewed.png)

#### Step 3 — Classify each condition

For each row, decide what the condition actually does:

| role | meaning | can it exclude? |
|---|---|---|
| **SCOPE_GATE** | Genuinely limits which documents the requirement governs | **Yes**, if separately authorised |
| **CONDITIONAL_OBLIGATION** | Describes *when* the duty triggers, not who it covers | No |
| **IMPLEMENTATION_QUALIFIER** | Describes *how* to satisfy it | No |
| **EXCEPTION_OR_WAIVER** | A carve-out from an otherwise applicable duty | No |
| **TEMPORAL_OR_SEQUENCE** | Timing or ordering | No |
| **UNKNOWN** | You cannot decide — routes to the policy owner queue | No |

**The distinction that matters.** *"For cloud-hosted systems"* is a scope gate — it says which systems
the rule covers. *"When a change is urgent"* is a conditional obligation — the rule still covers you, it
just fires under certain circumstances.

Treating the second as a scope gate would silently excuse a real requirement. Expect the model to
over-predict SCOPE_GATE; in one reviewed corpus it proposed 117 scope gates and human review confirmed
**zero**. Correcting it is the main work of this step.

#### Step 4 — Record your decisions in a CSV

One row per condition. Columns:

```csv
standard_version_id,requirement_set,requirement_id,condition_text,predicted_role,reviewer_role,reviewer_exclusion,reviewer_notes
```

| column | what to put |
|---|---|
| `standard_version_id` | the version you reviewed |
| `requirement_set` | the requirement set version, e.g. `REQSET-V3` |
| `requirement_id` | the requirement |
| `condition_text` | the condition, verbatim |
| `predicted_role` | what the model said |
| `reviewer_role` | **your** decision, from the table above |
| `reviewer_exclusion` | leave blank, or `AUTHORISE_EXCLUSION` — see step 6 |
| `reviewer_notes` | why. This is what a future reader sees |

What each `reviewer_role` value does:

| value | result |
|---|---|
| a specific role | Recorded as **confirmed** with that role |
| `UNKNOWN` | Routed to the **policy owner queue**; cannot exclude |
| **blank** | **No row written.** The model's guess stands, unreviewed and unable to exclude |

Blank is a safe default, not an omission — an unreviewed condition can never exclude anything.

#### Step 5 — Import as a dry run first

```bash
infrastructure/scripts/import-condition-overrides.py reviewed.csv \
  --batch CONDROLE-REVIEW-V1
```

Without `--apply` nothing is written; it prints the batch and a summary. **The importer refuses the
whole file if any row is malformed** — a half-applied review would leave some requirements governed and
others not, with nothing on screen to say which.

Check the summary, especially the authorised-to-exclude count. Then:

```bash
infrastructure/scripts/import-condition-overrides.py reviewed.csv \
  --batch CONDROLE-REVIEW-V1 --apply
```

#### Step 6 — Authorising an exclusion

This is the only action that removes a requirement from a review, so it takes **two independent
decisions** rather than one.

Put `AUTHORISE_EXCLUSION` in `reviewer_exclusion`. It is accepted only when the same row is a
**confirmed SCOPE_GATE**. Anything else is rejected with the line number.

```csv
...,SCOPE_GATE,AUTHORISE_EXCLUSION,"Standard covers cloud systems only; confirmed with the policy owner"
```

Why two decisions rather than one:

> Confirming *what a clause does* is not the same as authorising it to *remove a control from a review*.
> A reviewer may be certain a condition is a scope gate and still want a person to decide whether acting
> on it is appropriate.

The token is spelled out in full deliberately. A truthy `yes`, `y` or `1` would let a stray spreadsheet
cell grant a control-removing power by accident.

Even then, exclusion needs all four of:

1. the condition is a **confirmed SCOPE_GATE**;
2. exclusion is **explicitly authorised**;
3. the requirement's scope names **specific document types**, not "all";
4. the review ran with a **Document type** that clearly conflicts (Part 3, Step 2).

Miss any one and the result is **"applicability review required"** — a visible question, never a silent
exclusion.

<!-- SCREENSHOT: docs/images/condition-list-reviewed.png
     The same list after import: reviewed roles populated, provenance showing the batch id, and ideally
     one row where the reviewer DISAGREED with the extracted role. That disagreement is the whole reason
     this workflow exists. -->
![Conditions after review](docs/images/condition-list-reviewed.png)

#### Step 7 — Rerun and confirm

Reruns are needed: existing reviews are frozen and keep the decisions in force when they ran (Part 8).

Rerun with a **Document type** set, then check the coverage panel. A correctly excluded requirement
appears under **Not applicable** with your reason; an unresolved one appears under **Applicability
review required**.

> **The panel is read-only.** Decisions are recorded by the importer, not edited in the browser. That is
> deliberate: an exclusion needs a reviewed CSV with an author, notes and a batch id behind it, so a
> control removed from a review can always be traced to a person and a rationale.

---

---

## Part 7 — What this version does not do

Stated plainly, so you do not go looking:

| | |
|---|---|
| **Agentic retrieval (Phase 3)** | Disabled |
| **Automatic standard selection** | Available, but limited to one standard |
| **Shadow extraction sets** | Evaluated internally, never used in your reviews |
| **Overall compliance score** | Not produced, by design |

### Applicability review needs two things present

The applicability column stays empty unless **both** hold:

1. **You set a Document type** when running the review (Part 3, Step 2). Without it there is nothing to
   compare a requirement's scope against.
2. **The standard was indexed by a build that records applicability conditions.** Standards indexed by
   earlier builds have no condition data stored, so every requirement reads as simply applying.

If your environment shows an empty applicability panel and no scope questions anywhere, check the second
condition with an administrator before assuming the feature is broken — it is the more likely cause, and
re-uploading the standard does not fix it on its own.

### Known limitation in this release

Some requirements in the current standards corpus may have an obligation — usually a deadline — that
extraction failed to capture. Those surface as **"Extraction review required"** and are excluded from the
conclusion rather than guessed at.

A corrected extraction is planned as a separate, reviewed change. Until then the behaviour is honest
about the gap rather than hiding it.

---

## Part 8 — Things worth knowing

**Reviews are frozen.** A completed review records the prompt version, model, and governance decisions
that produced it. Reopening it months later shows what it concluded then — not a re-run under today's
settings. That is what makes a result auditable.

**Re-uploading the same file does nothing.** Content is hashed; an identical file is recognised rather
than re-indexed.

**Document content is never logged.** Diagnostics record counts and identifiers only.

**Evidence is loaded from storage, never from the model's response.** The AI returns passage
identifiers, and the text you see is fetched from the stored document. It cannot invent a quotation.

---

## Part 9 — Troubleshooting

| symptom | cause and fix |
|---|---|
| "Configure a model provider" | **Admin → AI & matching** is unset. Nothing runs until it is |
| Comparisons work but indexing fails with a 401 | The embedding provider needs the retrieval service restarted after a key change |
| Document uploaded but not reviewable | Parsing produced no passages — often an image-only scan. Re-export as text-based PDF |
| Review rejected for size | More requirements than the configured limit. Select fewer standards |
| A requirement says "not evaluated" | Use **Retry** on it, or **Rerun** the review |
| "Extraction review required" | A platform-side extraction gap. Retrying will not help; report it |
| Standard uploaded but no requirements | Indexing may still be running, or the file parsed to no usable text |
| Lots of "applicability review required" rows | **Document type** was left blank. Set it and rerun — see Part 3 Step 2 |
| Wrong standard recommended | Add context before selecting. Department and document type change the ranking |
| Cannot sign in | Not on the allowlist. Ask an administrator |
| A standard has vanished from selection | Likely archived. Archived versions stay readable in old reviews |

---

## Part 10 — Vocabulary

Several of these mean something narrower here than in ordinary use.

### Verdicts

| status | meaning |
|---|---|
| **Aligned** | The document satisfies the requirement, with supporting evidence cited |
| **Partial** | Some clauses satisfied, at least one not — and it always names which |
| **Missing** | No supporting passage found, in a document that *was* adequately searched |
| **Contradicted** | The document explicitly conflicts with the requirement |
| **Insufficient evidence** | The tool could not justify a conclusion, and says why |

**Missing versus insufficient evidence** is the distinction that matters most. "Missing" is a claim about
your document. "Insufficient evidence" is a claim about what the tool could establish. Reporting a
search failure as a compliance gap would be the most damaging mistake this tool could make, so when
coverage is incomplete it says so instead.

### Applicability

Held **separate** from the verdict, because they answer different questions: *should this be measured?*
versus *having measured it, does it comply?*

Forcing "does not apply" onto the compliance scale has no correct answer — "missing" would report a gap
the standard never demanded, "aligned" would claim compliance nobody assessed. So the two travel
alongside each other.

| | |
|---|---|
| **Applicable** | The requirement governs this document |
| **Not applicable** | Excluded by a governed decision, with a recorded reason |
| **Uncertain** | Nobody has established whether it applies. The finding is still shown |

### Coverage

| state | meaning |
|---|---|
| **Evaluated** | Compared, verdict produced |
| **Not evaluated** | No verdict: never attempted, or failed |
| **Not applicable** | Deliberately excluded, with a reason |
| **Applicability review required** | Compared, scope question open |
| **Extraction review required** | Compared, but our extraction of the standard was incomplete |

**Compared** and **counts toward the conclusion** are not the same thing. An extraction-review result was
measured and is visible, but is excluded from the conclusion, because an obligation in the standard went
unassessed.

---

## About the screenshots

Images live in `docs/images/`. Each is preceded by a comment saying what to capture and why.

Two things to check before capturing:

- **No real document content.** Use a document you are willing to publish. The side-by-side and findings
  images necessarily show document text, and this guide will be read by people who should not see your
  policies.
- **Mask the API key field** on the AI & matching image. The app never displays a stored key, but a
  screenshot taken mid-entry would.

Until the files exist the links render as broken placeholders. That is intentional — a visible gap gets
filled, whereas a commented-out link is forgotten.

### Capture list

Twenty-four images, in the order they appear. Each has a comment above it in the text saying what to frame
and **why** — the *why* matters more than the framing, because a screenshot that misses the point it
illustrates is worse than none at all.

| # | file | part |
|---|---|---|
| 1 | `roles-denied.png` | 1 — access denied is an explanation, not an error page |
| 2 | `history-reviewer-column.png` | 1 — reviewer column, showing scoping |
| 3 | `admin-new-standard.png` | 2 — the form, captured *before* upload |
| 4 | `derived-metadata-chips.png` | 2 — what the metadata is for |
| 5 | `bucket-layout.png` | 2 — drop-in upload location |
| 6 | `indexing-progress.png` | 2 — mid-run, stage visible |
| 7 | `upload-document.png` | 3 — a document ready to review |
| 7a | `context-inferred-chips.png` | 3 — the *guess*, before any editing |
| 7b | `context-fields.png` | 3 — context filled in, recommendations beside it |
| 8 | `select-standard.png` | 3 — auto-selection score **and** runner-up |
| 9 | `comparison-progress.png` | 3 — stage plus evaluated/total |
| 10 | `findings-list.png` | 3 — a *mix* of verdicts |
| 11 | `side-by-side.png` | 3 — cited passage highlighted |
| 12 | `coverage-panel.png` | 3 — **expanded**, showing the indented subsets |
| 13 | `extraction-review-entry.png` | 4 — note the deliberate absence of a Retry button |
| 14 | `partial-review-notice.png` | 4 — Retry offered on a genuinely failed requirement |
| 15 | `admin-ai-matching.png` | 5 — **mask the key field** |
| 16 | `admin-users.png` | 5 — mixed roles, blur addresses |
| 17 | `admin-standards-library.png` | 5 — include an archived version |
| 18 | `admin-prompts.png` | 5 — keep the version chip in frame |
| 19 | `admin-condition-governance.png` | 6 — ideally a row where the reviewer disagreed |
| 20 | `admin-policy-owner-queue.png` | 6 — a grouped entry |
| 21 | `condition-list-unreviewed.png` | 6 — the *before* state, reviewed roles blank |
| 22 | `condition-list-reviewed.png` | 6 — *after*, with provenance and a disagreement |

Four are worth extra care because the wrong crop inverts the meaning:

- **12** — a *collapsed* coverage panel misses the entire point of the layout.
- **13** — frame it so the reader can see **no Retry button is offered**; that absence is the point.
- **15** — mask the key field.
- **8** — the score-plus-runner-up pairing is what the surrounding text explains; either alone does not
  illustrate it.

Note that **13** and **19** need data that production does not currently have — an extraction-review
entry, and a reviewed condition. Capture those locally, or defer them until the corpus re-extraction
lands.
