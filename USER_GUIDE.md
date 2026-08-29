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

**You see your own reviews.** History is scoped to the account that ran each review. An administrator
does not silently see everyone's documents.

---

## Part 2 — Getting a standard into the system

A review needs at least one standard indexed first. Two ways in, both requiring the **standards admin**
role.

### Option A — Upload in the browser

**Standards** → add a standard → upload a version. Give it a title, category, and tags; these become the
chips you filter on later.

### Option B — Drop it in the cloud bucket

Files placed in the configured Google Cloud Storage bucket are picked up by a sweep that runs every five
minutes. Useful for bulk loading.

### Watching it index

Indexing extracts the standard's requirements, splits each into atomic clauses, and builds the search
vectors. A large standard takes a few minutes.

The status progresses through parsing → spans → chunks → embeddings → requirement extraction. If it
stalls, see troubleshooting in Part 9.

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

### Step 2 — Choose standards

Select one or more standards from the library. You can filter by category and tag.

**If you select none**, the tool picks the single best-matching standard for you and tells you which, with
its score. One, not several: selecting three standards that all derive from a common framework produces
the same control three times, and you cannot tell whether that is three problems or one.

The runner-up is still reported, with its score, so a close call stays visible and you can select it
explicitly.

**On size:** a review spanning more requirements than the configured limit (500 by default) is **rejected
up front**, telling you the count. It is not silently truncated — a review that quietly dropped a third of
its requirements while reporting "completed" is worse than one that refuses.

### Step 3 — Wait

Each requirement is retrieved and evaluated separately. Progress shows which stage it is in and how many
requirements are done.

A few hundred requirements takes several minutes. You can leave the page; the review continues.

### Step 4 — Read the findings

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

### Step 5 — Side-by-side view

Click a finding to see the standard's requirement beside your document, with the cited passages
highlighted. This is where you verify the tool rather than trust it.

### Step 6 — Read the coverage panel

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

### Retry and Rerun

- **Retry** re-evaluates a single failed requirement in place. The amended finding is stamped with the
  prompt and model versions it ran under, so a reader can see it was produced separately.
- **Rerun** creates a new review with the same document and standards, leaving the original intact.

---

## Part 5 — Administrator setup, in order

Nothing works until step 1. The order matters.

### 1. AI and matching — **required**

**Admin → AI & matching**: the model provider, server URL, API key, and model names.

Comparisons fail with a clear message until this is set. That is deliberate — a tool that silently fell
back to some default provider would send your documents somewhere you did not choose.

The key is **encrypted at rest** and never returned to the browser; the UI only shows whether one is set.

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

### 3. Standards library

**Admin → Standards library**: add, upload, activate, archive.

**Archive rather than delete** a superseded version. Reviews that already ran against it keep pointing at
it, and deleting it would leave those results citing a standard that no longer exists. An archived
version stops appearing in normal retrieval but remains readable for historical reviews.

### 4. Cloud sync — optional

**Admin → Cloud sync**: the bucket and the on/off switch for the five-minute sweep.

### 5. Prompts — read-only

**Admin → Prompts**: exactly what the AI is told, with version identifiers.

Deliberately not editable. These carry the rules that make findings checkable — that a verdict must cite
evidence that exists, that "partially satisfied" must name which clause falls short. Editing them freely
would produce responses that fail validation rather than merely read differently.

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

### Policy owner queue

**Admin → Policy owner queue**: conditions nobody has resolved yet, grouped so the same open question
across several standards appears once rather than as several small problems.

---

## Part 7 — What this version does not do

Stated plainly, so you do not go looking:

| | |
|---|---|
| **Agentic retrieval (Phase 3)** | Disabled |
| **Automatic standard selection** | Available, but limited to one standard |
| **Shadow extraction sets** | Evaluated internally, never used in your reviews |
| **Overall compliance score** | Not produced, by design |

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
