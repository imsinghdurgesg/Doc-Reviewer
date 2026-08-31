# Document review platform — user and administrator guide

This guide covers both audiences. **Parts 1–4** are for anyone reviewing a document; **Parts 5–6** are
for administrators. **Part 9** explains the vocabulary, which is worth reading once even if you only
review documents — several words mean something narrower here than in ordinary speech.

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
stalls, see troubleshooting in Part 8.

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

The tool recommends what to compare against, based on the context from step 2. You confirm or change it.

<!-- SCREENSHOT: docs/images/select-standard.png
     The recommendation panel: the recommended standard checked, the "Other standards considered"
     expander, and the "Choose from the full library instead" link — all three, because the point is
     that the ranked answer comes first and the alternatives are one click away rather than competing
     for attention. -->
![Choosing standards](docs/images/select-standard.png)

**What you see, in order:**

1. **Recommended** — normally one standard, already ticked. Each row shows its key, department,
   document type and tags, so you can tell at a glance whether it is the right body of rules.
2. **Other standards considered (N)** — a collapsed list of what the tool looked at and **the reason it
   set each one aside**. Worth opening if a standard you expected is missing; it tells you the standard
   was seen, and why it was not used.
3. **Choose from the full library instead (N)** — collapsed. Every indexed standard, for the case where
   the right answer was never a candidate at all.

**One recommendation, not several.** Selecting three standards that all derive from a common framework
produces the same control three times, and you cannot tell whether that is three problems or one. So the
tool proposes the single best match and leaves the rest one click away.

**You are not limited to the recommendation.** Tick anything you like from either collapsed list. The
recommendation is a ranking, not a gate — if you know a standard applies, select it.

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
| **Status** | the verdict — see Part 9 |
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

### Walkthrough — reviewing conditions on a standard

You do this in the browser. Open **Standards**, find the version you want, and click
**Update applicability** on its row.

<!-- SCREENSHOT: docs/images/applicability-row-link.png
     A standards-library version row with the "Update applicability" link visible beside Activate.
     Capture a row where the link is present; it only appears once a version is INDEXED. -->
![Update applicability on a version row](docs/images/applicability-row-link.png)

The action is **per version**, because a condition decision belongs to one extraction of one version.
It appears only once the version is indexed — before that there are no requirements, so no conditions.

#### Step 1 — Upload and index the standard

Part 2. Conditions are extracted alongside requirements, so there is nothing to review until indexing
finishes. If the dialog says the version has no requirement set yet, that is what it means.

#### Step 2 — Read what the tool guessed

Each condition shows as one block: the requirement it belongs to, the condition wording verbatim, and
a chip saying **"the tool guessed: …"**.

Treat that guess as a draft to challenge. **The tool over-predicts scope limits heavily** — in one
reviewed corpus it proposed 117 and a human confirmed **none of them**. Correcting it is most of the
work on this screen.

<!-- SCREENSHOT: docs/images/applicability-dialog.png
     The dialog open on a standard with several conditions: the summary chips, the guidance paragraph,
     and at least one condition block with its dropdown. Ideally a version where the tool's guess is
     wrong, since correcting it is the point. -->
![Applicability dialog](docs/images/applicability-dialog.png)

#### Step 3 — Say what each condition actually does

Pick from the dropdown. Each option asks a question rather than naming a category:

| option | example | can it cause a skip? |
|---|---|---|
| **It limits WHO the rule applies to** | "for cloud-hosted systems" | **Yes**, if you allow it in step 4 |
| **It says WHEN the rule kicks in** | "when a change is urgent" | No |
| **It says HOW to meet the rule** | "using the approved template" | No |
| **It is an exception to the rule** | "unless the CISO has granted a waiver" | No |
| **It is a deadline or an order of steps** | "within two business days" | No |
| **I am not sure** | — | No. Routes to the policy owner queue |

**The distinction the whole screen turns on.** *"For cloud-hosted systems"* limits **who** the rule
applies to. *"When a change is urgent"* only says **when** it kicks in — the rule still applies.
Marking the second as a limit would quietly excuse a real requirement, which is the failure this
screen exists to prevent.

A **reason is required** on any change. It is saved with your name, and it is what a reader sees months
later when they ask why a requirement was skipped.

#### Step 4 — Only if you chose "limits WHO"

A second question appears:

> **Should we stop checking this requirement for documents outside that scope?**

This is the only control in the product that can make a requirement vanish from a review, so it is a
**separate decision** from step 3. You may be certain a condition is a scope limit and still want a
person to confirm before the tool starts skipping controls.

<!-- SCREENSHOT: docs/images/applicability-exclusion-prompt.png
     The warning block that appears after choosing "It limits WHO the rule applies to", with the
     question, the explanation, and the "Yes, allow skipping" button. This is the highest-consequence
     control in the product, so capture the whole block including the text under the button. -->
![Allowing a requirement to be skipped](docs/images/applicability-exclusion-prompt.png)

**Leave it alone if you are unsure.** The requirement then stays in every review and the condition
appears as context. That is the safer outcome: a requirement you review and dismiss yourself costs a
minute, one the tool skipped by mistake is a gap nobody sees.

Even with it on, a requirement is skipped only when **all** of these hold:

1. you classified the condition as limiting who the rule applies to;
2. you allowed skipping;
3. the requirement names **specific** document types, not "all systems";
4. the review ran with a **Document type** that clearly falls outside that scope (Part 3, Step 2).

Miss any one and the review reports **"Applicability review required"** — a visible question for a
person, never a silent skip.

#### Step 5 — Save, then rerun

Click **Save decision**. It takes effect for **new** reviews; existing ones are frozen and keep the
decisions in force when they ran (Part 7).

Rerun with a **Document type** set, then check the coverage panel:

| where it appears | meaning |
|---|---|
| **Not applicable** | skipped, with your reason |
| **Applicability review required** | the scope question is still open |

#### Changing your mind

Every decision is **editable as often as you like**. Reclassify a condition, add or withdraw the
skip permission, or reword the reason — each save restamps who decided and when.

One safeguard worth knowing: if you reclassify a condition away from "limits WHO", the skip
permission is **withdrawn automatically** in the same edit. Otherwise a requirement would go on being
skipped on the strength of a classification that no longer exists.

#### Bulk review with a spreadsheet

For a large standard, `infrastructure/scripts/import-condition-overrides.py` imports many decisions
from a CSV in one go. It refuses the whole file if any row is malformed, and defaults to a dry run.

Editing a CSV-imported decision in the browser is allowed. When you do, the stored record stops naming
the source file: it would otherwise point at a spreadsheet whose contents no longer match the decision,
and a stale reference is more misleading than none. Who decided and when are still recorded.

#### If the dialog shows no conditions

Two things must both be true before there is anything to review:

1. **The standard finished indexing.** Conditions are extracted with the requirements.
2. **The standard was indexed by a build that records conditions.** Older standards have none stored,
   so every requirement simply applies.

If a standard clearly states conditions and the dialog shows none, it is almost certainly the second
reason. Re-uploading does not fix it on its own — ask an administrator to re-index the standard.

Separately: even a fully reviewed standard produces no applicability outcomes unless reviewers set a
**Document type** when running the review (Part 3, Step 2). Without it there is nothing to compare a
requirement's scope against.

#### Who can do this

**Standards admins and admins.** Deciding what a standard's conditions mean is curation of the
standards library — the authority a standards admin already holds.

**Admin → Applicability** remains available as a read-only view across every standard, which is where
to answer "how many requirements can be skipped anywhere?" The per-version dialog is where you work;
that tab is where you audit.

## Part 7 — Things worth knowing

**Reviews are frozen.** A completed review records the settings and decisions that produced it.
Reopening it months later shows what it concluded then — not a re-run under today's configuration. That
is what makes a result auditable.

**Re-uploading the same file does nothing.** Content is hashed; an identical file is recognised rather
than re-indexed.

**Your document content is never logged.** Diagnostics record counts and identifiers only.

**Evidence comes from storage, not from the model.** The AI returns passage identifiers, and the text
you see is fetched from your stored document. It cannot invent a quotation.

**One recommended standard, not several.** Comparing against three standards derived from a common
framework produces the same control three times, and you cannot tell whether that is three problems or
one. Anything else in the library is still one click away.

**No overall score.** The tool reports a verdict per requirement, with the evidence for each. A single
number would hide which requirements it rested on.

## Part 8 — Troubleshooting

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
| Wrong standard recommended | Add context before selecting (Part 3, Step 2). If it is still wrong, open **Other standards considered** — it says why each was set aside |
| A standard is missing from the list | Open **Other standards considered** first; if it is not there either, use **Choose from the full library instead**. A standard must be active and indexed to appear |
| Cannot sign in | Not on the allowlist. Ask an administrator |
| A standard has vanished from selection | Likely archived. Archived versions stay readable in old reviews |

---

## Part 9 — Vocabulary

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

Twenty-five images, in the order they appear. Each has a comment above it in the text saying what to frame
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
| 21 | `applicability-row-link.png` | 6 — the per-version link on a library row |
| 22 | `applicability-dialog.png` | 6 — the dialog, ideally where the tool guessed wrong |
| 23 | `applicability-exclusion-prompt.png` | 6 — **the highest-consequence control**; include the text under the button |

Four are worth extra care because the wrong crop inverts the meaning:

- **12** — a *collapsed* coverage panel misses the entire point of the layout.
- **13** — frame it so the reader can see **no Retry button is offered**; that absence is the point.
- **15** — mask the key field.
- **8** — the score-plus-runner-up pairing is what the surrounding text explains; either alone does not
  illustrate it.

Note that **13** and **19** need data that production does not currently have — an extraction-review
entry, and a reviewed condition. Capture those locally, or defer them until the corpus re-extraction
lands.
