/**
 * Translates extraction validation errors into guidance a document owner can act on.
 *
 * Architecture §12.8 lists the validation rules; the messages the validator emits are written for an
 * engineer reading a log ("duplicate requirementId within this requirement-set version"). The person
 * who has to FIX the problem is a standards author looking at their own document, and for them the
 * useful sentence names the thing in the document and what to change about it.
 *
 * Unrecognised messages are passed through verbatim rather than replaced with something vague — a
 * literal message someone can search for beats a friendly one that hides the cause.
 */

export interface ExplainedIssue {
  /**
   * The document text the issue concerns, when the message quotes it.
   *
   * Shown verbatim because it is the only unambiguous pointer: a section heading scopes the search,
   * but the sentence itself is what the author has to find and reword.
   */
  excerpt: string | null;
  /**
   * Where in the document the issue is — a requirement id ("3.1"), or a section label
   * ('Clause 5', 'Section "Offboarding"') for a skipped section. Null when the message carries no
   * location.
   */
  requirementId: string | null;
  /** What to do about it, in the document author's terms. */
  guidance: string;
  /** The original validator text, kept for support and for anything unrecognised. */
  raw: string;
  /** Whether this is a document-structure problem the author can fix. */
  actionable: boolean;
}

/**
 * Rules are ordered most-specific first and matched on a substring of the validator's message.
 *
 * Matching on text rather than a code because the validator emits plain strings today. If these ever
 * become typed codes, only this table changes — which is the reason the translation lives in one
 * place rather than inline in the component.
 */
const RULES: readonly { match: string; guidance: string; actionable: boolean }[] = [
  {
    match: "duplicate requirementId",
    guidance:
      "Two requirements were extracted with the same clause number. Check the document for a "
      + "repeated number (for example two clauses both labelled 5.1), or a section where several "
      + "separate obligations share one number — give each obligation its own number.",
    actionable: true,
  },
  {
    match: "duplicate clauseId",
    guidance:
      "One requirement produced two identical sub-clause identifiers. This usually means a sentence "
      + "repeats an obligation already stated in the same clause.",
    actionable: true,
  },
  {
    match: "no source span",
    guidance:
      "A requirement was extracted that could not be traced back to text in the document. It will "
      + "not be published, because a finding must be able to cite the exact wording it applies.",
    actionable: false,
  },
  {
    match: "was not supplied as evidence",
    guidance:
      "The extraction cited text that is not in this document version. Nothing needs fixing in your "
      + "document — the requirement was discarded rather than published with unverifiable wording.",
    actionable: false,
  },
  {
    match: "no atomic clauses",
    guidance:
      "A requirement was found but no testable obligation could be derived from it. This often means "
      + "the sentence describes intent rather than stating who must do what — for example “security "
      + "is important” rather than “the data owner must approve access”.",
    actionable: true,
  },
  {
    match: "has no action",
    guidance:
      "A clause was found with no action verb. Consider rewording it to state explicitly what must "
      + "be done, rather than describing a state of affairs.",
    actionable: true,
  },
  {
    match: "normalizedRequirement is empty",
    guidance:
      "A requirement had no statable content. Check for a heading or numbered line with no obligation "
      + "text following it.",
    actionable: true,
  },
  {
    match: "normativeStrength is missing",
    guidance:
      "The obligation strength could not be determined. Using explicit modal language — “must”, “must "
      + "not”, “shall” — makes a clause unambiguously mandatory.",
    actionable: true,
  },
  {
    match: "extractionConfidence",
    guidance:
      "The extraction reported an out-of-range confidence value. This is a model fault rather than a "
      + "document problem; re-uploading usually resolves it.",
    actionable: false,
  },
  {
    match: "so no requirement was extracted",
    guidance:
      "These sections were read as guidance, definitions, or examples, so no requirement was "
      + "extracted from them and comparisons will not check them. That is correct for genuine "
      + "guidance. If a clause here is meant to be binding, reword it with explicit obligation "
      + "language — “must”, “must not”, “shall” — and move it out of a Definitions section.",
    actionable: true,
  },
  {
    match: "definitions are not",
    guidance:
      "A definition or example was read as a mandatory control and rejected. If a clause in your "
      + "Definitions section is meant to be binding, move it into a numbered requirements section.",
    actionable: true,
  },
];

/**
 * The location prefix on a message, before the first ": ".
 *
 * Covers both shapes the backend produces: a bare requirement id from the validator ("3.1: …") and a
 * human label from a skipped section ('Clause 5: …', 'Section "5 Offboarding": …'). Both identify
 * WHERE in the document to look, which is the only reason the prefix is parsed at all — without it
 * the author gets advice with no location.
 */
const LOCATION_PREFIX =
  /^(.{1,60}?)\s+—\s+(.*)$|^((?:Clause|Section)\s+[^:]+|[A-Za-z0-9][A-Za-z0-9._-]*):\s*(.*)$/;

export function explainIssue(raw: string): ExplainedIssue {
  const match = LOCATION_PREFIX.exec(raw.trim());
  // Two shapes: an em-dash form from skipped sections ('5 Offboarding — read as …') and a colon form
  // from the validator ('3.1: duplicate requirementId …').
  const requirementId = match?.[1] ?? match?.[3] ?? null;
  const body = match?.[2] ?? match?.[4] ?? raw;

  const rule = RULES.find((r) => body.toLowerCase().includes(r.match.toLowerCase()));
  const quoted = /"([^"]{4,})"\s*$/.exec(body);

  return {
    requirementId,
    excerpt: quoted?.[1] ?? null,
    // Passed through when unrecognised: a literal message is searchable, a vague one is not.
    guidance: rule?.guidance ?? body,
    raw,
    actionable: rule?.actionable ?? false,
  };
}

/**
 * Groups issues so a document with the same problem in ten places reads as one item.
 *
 * Ten copies of "duplicate clause number" is one thing to fix, not ten — and an unscannable list is
 * how an administrator concludes the tool is broken rather than that their document needs an edit.
 */
export function summariseIssues(rawIssues: readonly string[]): {
  guidance: string;
  requirementIds: string[];
  excerpts: string[];
  actionable: boolean;
}[] {
  const grouped = new Map<string,
    { requirementIds: string[]; excerpts: string[]; actionable: boolean }>();

  for (const raw of rawIssues) {
    const explained = explainIssue(raw);
    const existing = grouped.get(explained.guidance);
    if (existing) {
      if (explained.requirementId) existing.requirementIds.push(explained.requirementId);
      if (explained.excerpt) existing.excerpts.push(explained.excerpt);
    } else {
      grouped.set(explained.guidance, {
        requirementIds: explained.requirementId ? [explained.requirementId] : [],
        excerpts: explained.excerpt ? [explained.excerpt] : [],
        actionable: explained.actionable,
      });
    }
  }

  return [...grouped.entries()]
    .map(([guidance, v]) => ({ guidance, ...v }))
    // Actionable items first: those are the ones worth the author's attention.
    .sort((a, b) => Number(b.actionable) - Number(a.actionable));
}
