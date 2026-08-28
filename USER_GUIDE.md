# Reviewing a document against a standard — user guide

**https://process-lens.pochub-ai.com**

Sign in with your Stanford Google account. Everyone who signs in can upload documents, run reviews,
and read their own results. Managing standards and settings needs an admin role.

---

## What this tool does

You have a **process document** — a procedure, a runbook, a policy of your own. Somewhere there is a
**standard** it is supposed to comply with. This tool reads the standard, breaks it into individual
requirements, and checks your document against each one, showing you the exact passages behind every
verdict.

It is not a keyword search. A requirement saying *"changes must be approved before deployment"* is
matched against the meaning of your document, not the word "approved".

---

## Part 1 — Getting a standard into the system

Nothing can be reviewed until at least one standard is **indexed**. There are two ways in.

### Option A — Upload through the browser

**Admin → Standards library → New standard**

<!-- SCREENSHOT: docs/images/admin-new-standard.png
     The New standard dialog, filled in. Show Category and Tags populated, since those are the fields
     users most often leave blank and the ones that most affect matching. -->
![New standard dialog](docs/images/admin-new-standard.png)

| Field | Needed? | Why it matters |
|---|---|---|
| Standard key | yes | Permanent identifier, e.g. `GRC-CHG-2026`. Appears in findings and cannot be changed. |
| Title | yes | |
| Owner | no | Recorded for reference. Not used in matching. |
| Description | no | **Used for matching.** |
| Category | no | **Used for matching.** e.g. `Change Management` |
| Tags | no | **Used for matching.** Comma-separated, e.g. `change, approval, rollback` |
| Business unit | no | Reporting only. Not used in matching. |

Then **upload a version** — the document itself, with a version label like `3.0`.

> **Filling in description, category, and tags is the single most useful thing you can do.** They are
> what the system uses to decide which standard governs an uploaded document. A standard with only a
> title is much harder to match correctly.

### Option B — Drop it in the Google Cloud Storage bucket

Bucket: **`stanford-docreview-standards`**

Path layout — this is not optional, the folders *are* the identifiers:

```
standards/<standard-key>/<version>/<filename>
```

For example:

```
standards/technology-change-management/3.0/GRC-CHG-2026_Standard_v3.0.md
```

<!-- SCREENSHOT: docs/images/bucket-layout.png
     The GCS browser showing standards/<key>/<version>/<file>. The folder nesting is the part people
     get wrong, and it is far clearer seen than described. -->
![Bucket folder layout](docs/images/bucket-layout.png)

- `technology-change-management` becomes the standard key **and** the title
- `3.0` becomes the version label

The system checks the bucket **every 5 minutes**. New files are picked up automatically; you do not
need to do anything else.

**Requires Cloud sync to be switched on** — Admin → Cloud sync → Enabled. If it is off, nothing in the
bucket is ever read.

#### What happens about metadata

A bucket file has no form to fill in, so the system **reads the document and writes its own**
description, category, and tags. For a change-management standard it produced:

```
category: Change Management
tags:     production changes, change approval, risk assessment, deployment,
          emergency changes, evidence retention, rollback, ai changes
```

These appear as chips on the standards list. **You can see them, and you should check them** — they
were inferred, so they can be wrong, and a mis-categorised standard becomes hard to find.

<!-- SCREENSHOT: docs/images/derived-metadata-chips.png
     A standards-list row with the category chip and tag chips visible. Ideally beside a row that has
     none, so the difference between a described standard and a title-only one is obvious. -->
![Category and tag chips on a standard](docs/images/derived-metadata-chips.png)

If you fill in the fields yourself, yours are kept. The system only fills what is blank.

### Watching it index

The standards list shows the stage. It takes a few minutes: the document is parsed, split into
passages, then one AI call per candidate requirement group.

| State | Meaning |
|---|---|
| `PARSING_STANDARD` … `GENERATING_EMBEDDINGS` | Working through the pipeline |
| **`INDEXED`** | **Ready to compare against** |
| `NEEDS_REVIEW` | Indexed, but some requirements failed validation — check the listed issues |
| `FAILED` | Nothing extracted. Usually a scanned PDF: its pages are images, so there is no text |

<!-- SCREENSHOT: docs/images/indexing-progress.png
     A version mid-pipeline — EXTRACTING_REQUIREMENTS or similar — next to one already INDEXED. The
     point is that a stage label is not an error, which is what people assume when it sits there. -->
![Indexing progress](docs/images/indexing-progress.png)

Then **Activate** the version. Only ACTIVE + INDEXED versions are used in comparisons — this is
deliberate, because an active standard with no requirements would produce a review with no findings,
which reads as full compliance.

---

## Part 2 — Reviewing your document

### Step 1 — Upload it

Accepted: **`.pdf` `.docx` `.txt` `.md` `.markdown` `.csv` `.json`** — up to 50 MB, 2000 pages.

<!-- SCREENSHOT: docs/images/upload-document.png
     The upload screen with a file selected. -->
![Uploading a process document](docs/images/upload-document.png)

> A **scanned PDF will not work.** If the pages are images there is no text to read. Check by trying
> to select text in a PDF viewer; if you cannot, this tool cannot either.

### Step 2 — Choose a standard, or don't

**If you pick one**, that is what your document is measured against.

<!-- SCREENSHOT: docs/images/select-standard.png
     The standard picker, showing the "leave blank for automatic selection" state. Both paths should
     be visible in one image if possible, since the choice is the thing being explained. -->
![Choosing a standard](docs/images/select-standard.png)

**If you leave it blank**, the system picks for you:

1. It reads the first ~40 passages of your document — title, purpose, scope, headings
2. Compares that against every indexed standard
3. Selects **the single best match**, provided it is relevant enough

If nothing is relevant enough, the review stops with *"No standard in the library was relevant enough
to compare against"* rather than guessing. That is intentional — comparing against an unrelated policy
would report every requirement as a gap.

Only ever **one** standard is auto-selected, even when several score identically. Comparing against
three standards produced 118 findings instead of 65, with the same control appearing three times from
three sources — impossible for a reviewer to interpret. Runners-up are still listed with their scores
if you want to pick one by hand.

### Step 3 — Wait

You will see a stage label and, once evaluation begins, a real count: **`16 of 52 requirements`**.

<!-- SCREENSHOT: docs/images/comparison-progress.png
     Mid-run, showing "N of M requirements" and the stage label. Capture it with a real count on
     screen — a static number is what tells the reader the bar is measured, not decorative. -->
![Comparison in progress](docs/images/comparison-progress.png)

Roughly 5 seconds per requirement, four at a time — so a 40-requirement standard takes about a minute.
The number is measured, not estimated. If it is moving, the review is working.

### Step 4 — Read the findings

<!-- SCREENSHOT: docs/images/findings-list.png
     The findings panel with a mix of verdicts — ideally at least one PARTIAL so its gap type shows. -->
![Findings list](docs/images/findings-list.png)

Each requirement gets one verdict:

| Verdict | Meaning |
|---|---|
| **ALIGNED** | Your document satisfies it |
| **PARTIAL** | Partly satisfied — see the gap type for which part failed |
| **MISSING** | Not addressed |
| **CONTRADICTED** | Your document says something incompatible |
| **INSUFFICIENT_EVIDENCE** | Could not be determined |

**`INSUFFICIENT_EVIDENCE` is not a gap.** It means the tool could not tell — scope was unclear, or your
document referred to something that was not supplied. Treating it as a gap asserts something that was
never established.

For `PARTIAL`, the gap type says what specifically fell short: `WEAKER`, `INCOMPLETE`,
`CONDITIONAL_ONLY`, `TIMING_MISMATCH`, `RESPONSIBILITY_MISMATCH`, `EVIDENCE_NOT_DOCUMENTED`, or
`SCOPE_MISMATCH`.

### Step 5 — Side-by-side view

<!-- SCREENSHOT: docs/images/side-by-side.png
     THE key image. A finding selected, both panes scrolled to their passage, both highlights visible.
     Worth choosing a finding whose highlight is short enough to see clearly at screenshot scale. -->
![Side-by-side review](docs/images/side-by-side.png)

Click a finding and both panes open:

- **Left** — your document, scrolled to the relevant passage, highlighted
- **Right** — the standard, scrolled to the requirement, highlighted

The two panes scroll independently by default. The link icon in the toolbar couples them if you want
that — though a two-page procedure and a forty-page standard rarely correspond position-for-position.

**Everything highlighted is real text from your document.** The AI is not allowed to quote — it returns
only *pointers* to passages it was shown, and the system looks up the actual text. An invented citation
fails and never reaches you.

For a `MISSING` finding nothing highlights, and a note explains why, usually naming the closest
passage considered.

---

## Part 3 — Reading a partial review

You may see:

> **2 of 41 requirements could not be evaluated.** Treat this as an incomplete review — an absent
> finding here does not mean compliance.

<!-- SCREENSHOT: docs/images/partial-review-notice.png
     The warning banner with the named unevaluated controls beneath it. The naming is the point — a
     count alone is what this replaced. -->
![Partial review notice](docs/images/partial-review-notice.png)

That means 39 findings are valid and 2 controls were **not checked**. The unchecked ones are named, so
you can review them by hand.

This is deliberate. The alternative — silently reporting 39 — would let you believe the review was
complete when it was not.

The message only appears once a review has **finished**. While it is running, the remaining
requirements simply have not been reached yet.

---

## Part 4 — Things worth knowing

### What you can see

You see **your own** reviews. Admins see everyone's. History shows who ran each review, and you can
search by their name.

<!-- SCREENSHOT: docs/images/history-reviewer-column.png
     The history table with the Reviewer column populated. Take this as an admin, so more than one
     reviewer appears — as a reviewer every row would be you, which shows nothing. -->
![History with the Reviewer column](docs/images/history-reviewer-column.png)

### Reviews are frozen

Reopening a review shows the saved result. It never re-runs, and never substitutes a newer version of
the standard. Every review records which model, prompt, standard version, and index produced it — so a
finding stays explainable months later.

### The tool does not judge your document overall

It answers one requirement at a time. There is no compliance score, and that is deliberate: a single
number hides which specific obligation failed, which is the only actionable part.

### Aligned requirements are kept, not discarded

A review showing mostly ALIGNED is a useful result. Those are filtered out of the work list but stay
available — you need them to show what *was* checked.

---

## Quick troubleshooting

| Problem | Cause |
|---|---|
| *"No standard was relevant enough"* | No indexed standard matched. Index one, or select it manually. |
| *"If this is a scanned PDF…"* | No text extracted. Scanned pages are images. |
| Standard stuck mid-pipeline | Indexing makes one AI call per requirement group. Give it a few minutes. |
| Bucket file never appears | Cloud sync is disabled, or the path is not `standards/<key>/<version>/<file>`. |
| Comparison fails immediately | No AI provider configured — Admin → AI & matching. |
| Admin fields greyed out | You have the reviewer role. Roles are read at sign-in, so a new role needs a fresh sign-in. |
| Re-uploading the same file does nothing | Content is hashed; an identical file is recognised, not re-indexed. |

---

## For admins

<!-- SCREENSHOT: docs/images/admin-ai-matching.png
     The AI & matching tab: provider, server URL, key state, model aliases, and the three instruction
     boxes. Mask the key field. -->
![AI and matching settings](docs/images/admin-ai-matching.png)

**Admin → AI & matching** — the model provider, key, and model names. Nothing works until this is set.
Also three optional **instruction** boxes that let you steer how the AI judges compliance, splits
requirements, and describes standards. They are appended to the built-in rules, which always win.

<!-- SCREENSHOT: docs/images/admin-prompts.png
     The Prompts tab with one prompt expanded, its version chip visible. -->
![Prompts tab](docs/images/admin-prompts.png)

**Admin → Prompts** — read-only view of exactly what the AI is told, with version identifiers. Not
editable: these carry the rules that make findings checkable, and changing them would make responses
fail validation rather than merely read differently.

**Admin → Standards library** — add, upload, activate. Category and tags show as chips.

**Admin → Cloud sync** — the bucket, and the on/off switch for the 5-minute sweep.

> Changing the provider or key in Admin takes effect immediately for comparisons, but **embeddings need
> the retrieval service restarted** before they follow. Until then, indexing and comparison could be
> talking to different providers.


---

## About the screenshots

Images live in `docs/images/`. Each is preceded by an HTML comment saying what to capture and why —
the *why* matters more than the framing, because a screenshot that misses the point it illustrates is
worse than none.

Two things to check before capturing:

- **No real document content.** Use a document you are willing to publish. The side-by-side and
  findings images necessarily show document text, and this guide will be read by people who should not
  see your policies.
- **Mask the API key field** on the AI & matching image, even though the app never displays a stored
  key — a screenshot taken mid-entry would.

Until the files exist the image links render as broken placeholders. That is intentional: a visible gap
gets filled, whereas a commented-out link is forgotten.
