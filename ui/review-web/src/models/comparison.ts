/**
 * Domain types mirroring the backend contracts.
 *
 * Hand-written rather than generated: §7.3 lists `generated-types.ts` as the eventual home, but a
 * generator needs a published OpenAPI document, and an inaccurate generated file is worse than an
 * accurate hand-written one. These mirror platform-contracts exactly.
 */

/** Five statuses. Architecture §17. */
export type ComparisonStatus =
  | "ALIGNED"
  | "PARTIAL"
  | "MISSING"
  | "CONTRADICTED"
  | "INSUFFICIENT_EVIDENCE";

/** Why a PARTIAL is partial (§17.2). Present only for PARTIAL. */
export type GapType =
  | "WEAKER"
  | "INCOMPLETE"
  | "CONDITIONAL_ONLY"
  | "TIMING_MISMATCH"
  | "RESPONSIBILITY_MISMATCH"
  | "EVIDENCE_NOT_DOCUMENTED"
  | "SCOPE_MISMATCH";

export type Severity = "HIGH" | "MEDIUM" | "LOW" | "INFORMATIONAL";

/**
 * A rectangle on a rendered page, in PDF points.
 *
 * TOP-DOWN: `y` is measured from the page's top edge. A consumer places a box with `top = y` and
 * never `top = pageHeight - y`. Applying a bottom-up flip puts every box at `pageHeight - y`, which
 * near the middle of a page reads as a rounding error rather than an inverted axis.
 */
export interface BoundingBox {
  page: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * How precisely a span's rectangles locate its text.
 *
 * Sent by the backend rather than inferred here: a non-empty box list is not by itself a claim of
 * precision, because boxes may bound the containing paragraph rather than the cited sentence. The
 * viewer must be able to say which it is.
 */
export type HighlightQuality = "EXACT" | "APPROXIMATE" | "PAGE_ONLY" | "UNRESOLVED";

/**
 * The convention `boundingBoxes` are expressed in.
 *
 * Only one value exists today. It is transmitted so the viewer can refuse to draw geometry in a
 * convention it does not know how to place, rather than placing it wrongly.
 */
export type CoordinateSpace = "PDF_POINTS_TOP_LEFT";

/** Page dimensions and rotation the rectangles were measured against. */
export interface PageGeometry {
  pageNumber: number;
  /** CROP-box width in PDF points — the dimension the viewer lays out. */
  width: number;
  height: number;
  /** `/Rotate`, normalised to 0, 90, 180, or 270. */
  rotation: number;
}

/**
 * Hydrated evidence (§19.1).
 *
 * Text and boxes come from stored spans, never from the model response. The model returns only span
 * IDs, so displayed evidence is verifiable rather than asserted.
 *
 * The geometry fields are optional: spans persisted before they existed deserialise without them,
 * and a consumer treats their absence as "precision unknown" rather than as a default of exact.
 */
export interface HydratedEvidence {
  spanId: string;
  page: number | null;
  sectionPath: readonly string[];
  text: string;
  boundingBoxes: readonly BoundingBox[];
  highlightQuality?: HighlightQuality | null;
  coordinateSpace?: CoordinateSpace | null;
  /**
   * Hash of the rendition the boxes were measured against.
   *
   * Compared against the PDF the viewer actually loaded. A mismatch means the rectangles came from a
   * different render — pagination and line wrapping can change while the text stays identical — so
   * they must not be drawn.
   */
  renditionSha256?: string | null;
  pageGeometry?: PageGeometry | null;

  /**
   * Node anchor for the HTML pane.
   *
   * `charStart`/`charEnd` are offsets into the DOCUMENT's character stream, not into the node — the
   * parser emits them as `nodeStart + rangeWithinNode`. Subtracting the node's own `charStart`
   * inverts that exactly, which is what makes highlighting a lookup rather than a text search.
   *
   * Unlike the geometry fields these survive re-rendering, so the HTML pane works for document types
   * that have no PDF rendition at all.
   */
  parentNodeId?: string | null;
  charStart?: number | null;
  charEnd?: number | null;
  /**
   * Hash of the span's text.
   *
   * The HTML pane's counterpart to `renditionSha256`: compared against the node it is about to mark,
   * so a re-parse that shifted offsets suppresses the highlight instead of marking the wrong
   * sentence.
   */
  textHash?: string | null;
}

/** One structural element of a parsed document, for the HTML review pane. */
export interface CanonicalNodeView {
  nodeId: string;
  nodeType: string;
  sectionPath: readonly string[];
  page: number | null;
  paragraph: number | null;
  ordinal: number;
  text: string;
  textHash: string | null;
  /** -1 when no span points at this node, so no offset is claimed. */
  charStart: number;
  charEnd: number;
}

/** A document version as reflowable nodes (§11.2, §19.1). */
export interface CanonicalDocumentView {
  documentVersionId: string;
  title: string | null;
  contentHash: string | null;
  pages: number;
  nodes: readonly CanonicalNodeView[];
}

/** Where a highlight should be drawn in the HTML pane, or why it cannot be. */
export interface ResolvedHighlight {
  nodeId: string;
  start: number;
  end: number;
  quality: HighlightQuality;
}

/**
 * Turns a span's document-absolute range into a node-relative one.
 *
 * Returns `null` when the highlight must not be drawn. Three cases, all of which are real:
 *
 * - the span names no node, or names one the view filtered as repeated content;
 * - the node carries no offset (`-1`), meaning no span was recorded against it;
 * - the text at the resolved range is not the span's text, meaning a re-parse shifted the offsets.
 *
 * The last is the important one. Drawing anyway would mark a plausible-looking but wrong sentence,
 * which is precisely the failure mode that fuzzy re-location produces and this design avoids. When
 * in doubt the pane shows the finding with no highlight rather than a confident wrong one.
 */
export function resolveHighlight(
  evidence: HydratedEvidence,
  nodesById: ReadonlyMap<string, CanonicalNodeView>,
): ResolvedHighlight | null {
  const { parentNodeId, charStart, charEnd } = evidence;
  if (!parentNodeId || charStart == null || charEnd == null) {
    return null;
  }

  const node = nodesById.get(parentNodeId);
  if (!node || node.charStart < 0) {
    return null;
  }

  const start = charStart - node.charStart;
  // Clamped to the node's end. A span whose range runs one or two characters past its node is
  // normal: `SpanBuilder` strips the span text, so a node with leading or trailing whitespace
  // yields a range slightly wider than the text it names. The verification below is what decides
  // whether the clamp landed correctly — it compares the selected text against the span's own, so
  // an over-clamp fails there rather than being drawn.
  const end = Math.min(charEnd - node.charStart, node.text.length);
  if (start < 0 || end <= start) {
    // Out of bounds means the offsets do not describe this node's text. Clamping would produce a
    // highlight that looks deliberate; refusing says plainly that the location is unknown.
    return null;
  }

  // The span's text must still be the text at those offsets. `textHash` covers the span, not the
  // node, so the check is a direct comparison of what the range selects against what the span says
  // it cites. A re-parse that shifted offsets fails here rather than marking the wrong sentence.
  if (node.text.slice(start, end).trim() !== evidence.text.trim()) {
    // Out of bounds means the offsets do not describe this node's text. Clamping would produce a
    // highlight that looks deliberate; refusing says plainly that the location is unknown.
    return null;
  }

  return {
    nodeId: node.nodeId,
    start,
    end,
    // Offsets resolved cleanly. The span's own quality describes RECTANGLE precision, which says
    // nothing about a character range, so it is not carried over here.
    quality: "EXACT",
  };
}

/** One independently testable element of a requirement (§18.5). */
/**
 * Whether a clause's `reason` is genuinely its own, rather than the parent's copied onto it.
 *
 * Schema V1 returned one reason per requirement and it was written to every clause, so a three-clause
 * finding displayed the same multi-clause paragraph three times. New V1 results leave the field null;
 * stored ones still carry the copy, and rewriting them would break §19.2 reproducibility — so the
 * duplication is detected at READ time instead.
 *
 * The test is whether the text is shared across clauses, NOT whether it looks generic. Two clauses
 * may legitimately have the same explanation; what is never legitimate is every clause in a
 * multi-clause finding sharing one string, because that is the signature of inheritance.
 */
export function isClauseSpecificReason(
  clause: AtomicClauseResult,
  all: readonly AtomicClauseResult[],
): boolean {
  if (!clause.reason) {
    return false;
  }
  // A single clause cannot have inherited-vs-own ambiguity: its reason is the requirement's, and
  // showing it once here is correct.
  if (all.length < 2) {
    return true;
  }
  const distinct = new Set(all.map((c) => c.reason).filter(Boolean));
  // One distinct value across several clauses means it was copied, not attributed.
  return distinct.size > 1;
}

/** Whether any clause in this finding carries explanation the model attributed to it. */
export function hasClauseSpecificDetail(all: readonly AtomicClauseResult[]): boolean {
  return all.some((c) => isClauseSpecificReason(c, all));
}

export interface AtomicClauseResult {
  clauseId: string;
  /**
   * Readable rendering of the clause, derived from its stored fields by the backend.
   *
   * Null on comparisons stored before the field existed. Without it, two sibling clauses of one
   * requirement — "publish" and "observe" — render as identical rows differing only by an ID suffix,
   * so a reviewer cannot see which obligation failed.
   */
  clauseText?: string | null;
  status: ComparisonStatus;
  gapType: GapType | null;
  /**
   * Clause-specific supporting evidence.
   *
   * Empty under schema V1: the model attributed evidence to the requirement, not to each clause, so
   * populating this would claim traceability that was never established.
   */
  processSpanIds: readonly string[];
  /** Clause-specific explanation. Null under schema V1 — see {@link isClauseSpecificReason}. */
  reason: string | null;
}

/** A validated finding (§18.6). */
export interface Finding {
  findingId: string;
  comparisonId: string;
  standardVersionId: string;
  requirementSetVersion: string;
  requirementId: string;
  parentControlId: string | null;
  requirementTitle: string;
  status: ComparisonStatus;
  gapType: GapType | null;
  severity: Severity;
  standardSpanIds: readonly string[];
  processSpanIds: readonly string[];
  /**
   * For an absence verdict, the closest passage that WAS considered. Without it a MISSING finding is
   * unfalsifiable — the reviewer cannot tell whether the tool looked in the right place.
   */
  nearestProcessSpanIds: readonly string[];
  clauseResults: readonly AtomicClauseResult[];
  reason: string;
  recommendedAction: string | null;
  confidence: number;
  needsHumanReview: boolean;

  /**
   * Whether the requirement APPLIED, separate from whether it was satisfied.
   *
   * Null on findings recorded before applicability existed — {@link findingApplicability} normalises
   * that rather than making every caller decide what a missing value means.
   */
  applicabilityStatus: ApplicabilityStatus | null;

  /** Why the requirement was excluded or is unclear. Null when it simply applies. */
  applicabilityReason: string | null;

  /**
   * What decided applicability: resolver version, plus any governed review batches applied.
   *
   * Shown so a reviewer can tell a machine-only decision from one a person signed off. Reopening an
   * old comparison then shows the decision that ran, not today's.
   */
  applicabilityDecidedBy: string | null;
}

/**
 * Whether a requirement applied. Deliberately NOT part of {@link ComparisonStatus}.
 *
 * Forcing "does not apply" onto the compliance scale has no correct answer: MISSING reports a gap the
 * standard never demanded, and ALIGNED claims compliance nobody assessed.
 */
export type ApplicabilityStatus = "APPLICABLE" | "NOT_APPLICABLE" | "UNCERTAIN";

/**
 * A finding's applicability, defaulting to APPLICABLE.
 *
 * APPLICABLE is right twice over: it is the ordinary case, and a finding exists at all only because the
 * requirement was compared — which happens only when it applied.
 */
export function findingApplicability(finding: Finding): ApplicabilityStatus {
  return finding.applicabilityStatus ?? "APPLICABLE";
}

/**
 * True when applicability itself needs a human decision, not the compliance verdict.
 *
 * A reviewer needs this separated from `needsHumanReview`: "the model was unsure whether you comply" and
 * "nobody has established whether this rule governs your document at all" call for different actions.
 */
export function needsScopeReview(finding: Finding): boolean {
  return findingApplicability(finding) !== "APPLICABLE";
}

/** True when a governed review batch, not just the rule engine, shaped this decision. */
export function isGovernedDecision(finding: Finding): boolean {
  return (finding.applicabilityDecidedBy ?? "").includes("+");
}

/** A standard evaluated within a comparison. */
export interface ComparisonReference {
  standardId: string;
  standardVersionId: string;
  standardKey: string;
  standardTitle: string;
  standardVersion: string;
  /** Relevance, NOT compliance (§15.1). Rendering this as a compliance score would be a lie. */
  retrievalScore: number;
  renderUrl: string | null;
  textUrl: string | null;
  findings: readonly Finding[];
}

/**
 * A requirement the run could not evaluate, split from its `requirementId: reason` wire form.
 *
 * Named, not just counted: "1 of 15 could not be evaluated" leaves a reviewer unable to judge
 * whether the gap matters, because the missing control might be the important one.
 */
export interface UnevaluatedRequirement {
  requirementId: string;
  reason: string;
}

/** Splits `"CHG-04-2: PARTIAL requires at least one unsatisfied clause"` into its parts. */
export function parseUnevaluated(entry: string): UnevaluatedRequirement {
  const at = entry.indexOf(": ");
  if (at < 0) {
    // No delimiter: show the whole string as the id rather than silently dropping it.
    return { requirementId: entry.trim(), reason: "" };
  }
  return {
    requirementId: entry.slice(0, at).trim(),
    reason: entry.slice(at + 2).trim(),
  };
}

export interface ComparisonDetail {
  comparisonId: string;
  publicId: string;
  status: string;
  document: {
    documentVersionId: string;
    filename: string;
    pageCount: number | null;
    renderUrl: string | null;
  };
  references: readonly ComparisonReference[];
  /** Recorded so a historical comparison resolves exactly what produced it (§19.2). */
  comparisonModelAlias: string | null;
  promptVersion: string | null;
  requirementSetVersion: string | null;
  retrievalStrategyVersion: string | null;
  /** Progress while evaluation runs (§21). Null before the worker reports. */
  evaluatedRequirements: number | null;
  totalRequirements: number | null;
  stateMessage: string | null;
  /**
   * `requirementId: reason` for each requirement the run could not evaluate (§18.4).
   *
   * Optional: comparisons stored before the field existed omit it, and an older backend does not
   * send it — absence means "unknown", not "none failed", so the count remains the source of truth
   * for whether a gap exists.
   */
  unevaluatedRequirements?: readonly string[];
  /**
   * The reviewer's assessment focus, verbatim. Null when none was given.
   *
   * <p>Guidance changes which findings exist, so a reader who cannot see it cannot tell a focused
   * review from an unfocused one. Displayed read-only — the inputs of a completed review are fixed.
   *
   * <p>Optional on the type because comparisons created before this field existed have no value, and
   * a required field would make every one of them fail to parse.
   */
  comparisonGuidance?: string | null;
  /**
   * Requirement-by-requirement coverage (§17.6).
   *
   * Null — or absent on an older backend — means coverage was never recorded. That must render as
   * "not recorded", never as full coverage: "nothing was measured" and "everything passed" look
   * identical in a summary that treats a missing ledger as zero gaps.
   */
  coverage?: CoverageSummary | null;
  /**
   * True when this comparison predates coverage tracking and legitimately has no summary.
   *
   * Sent by the server rather than inferred from `coverage == null`, so the UI never guesses whether a
   * missing summary is expected. The two cases need different words: "predates coverage tracking"
   * versus "something is wrong with this review".
   */
  coverageExempt?: boolean;
  createdAt: string;
  completedAt: string | null;
}

/**
 * How much of the selected standards was actually measured.
 *
 * Counts come from the server rather than being derived here: two clients computing them
 * independently is two chances to disagree about whether a review was complete.
 */
export interface CoverageSummary {
  totalRequirements: number;
  /**
   * Requirements the comparison actually ran against — the PARENT of the two fields below.
   *
   * `compared === applicabilityConfirmed + applicabilityReviewRequired`, always. Rendered as a parent
   * with indented children so the subset relationship is visible in the layout: four sibling numbers
   * invite a reader to add them and reach a total larger than the whole.
   */
  compared: number;
  applicabilityConfirmed: number;
  /** A SUBSET of `compared`, not a separate outcome. */
  applicabilityReviewRequired: number;
  /** Excluded by a governed applicability decision. Disjoint from `compared`. */
  notApplicable: number;
  /** Produced no verdict at all: never attempted, or failed. Disjoint from `compared`. */
  notEvaluated: number;
  complete: boolean;
  /** Only entries needing attention; plainly evaluated requirements are omitted. */
  uncovered: readonly CoverageEntry[];
}

/**
 * Whether the counts account for every requirement exactly once.
 *
 * Checked in the UI as well as the server: a summary that does not add up is a coverage claim with a
 * hole in it, and the hole is invisible in any single number. Better to say so than to render numbers
 * a reviewer would reasonably trust.
 */
export function coverageReconciles(c: CoverageSummary): boolean {
  return (
    c.compared + c.notApplicable + c.notEvaluated === c.totalRequirements &&
    c.applicabilityConfirmed + c.applicabilityReviewRequired === c.compared
  );
}

export interface CoverageEntry {
  requirementId: string;
  requirementTitle: string | null;
  standardVersionId: string;
  state: "PENDING" | "FAILED" | "SKIPPED" | "UNCERTAIN_APPLICABILITY" | "EVALUATED";
  reason: string | null;
}

/** Reviewer-facing label and explanation for a coverage state. */
export function coverageStateLabel(state: CoverageEntry["state"]): string {
  switch (state) {
    case "PENDING":
      return "Not evaluated";
    case "FAILED":
      return "Not evaluated";
    case "SKIPPED":
      return "Not applicable";
    case "UNCERTAIN_APPLICABILITY":
      return "Applicability review required";
    default:
      return "Evaluated";
  }
}

/**
 * True when this entry means a requirement went unmeasured.
 *
 * Distinguishes the two legitimate non-evaluations from the two gaps: NOT_APPLICABLE and
 * UNCERTAIN_APPLICABILITY are decisions, while PENDING and FAILED are absences of one.
 */
export function isCoverageGap(entry: CoverageEntry): boolean {
  return entry.state === "PENDING" || entry.state === "FAILED";
}

/**
 * An uploaded process document (§14).
 *
 * `readyForComparison` is the backend's own gate, not a client-side guess: a document with no
 * evidence spans cannot be compared, because retrieval would find nothing and every requirement
 * would come back MISSING — which reads as total non-compliance rather than "not parsed yet" (§17.5).
 */
export interface UploadedDocumentSummary {
  documentVersionId: string;
  filename: string;
  mediaType: string | null;
  sizeBytes: number;
  state: string;
  stateMessage: string | null;
  pageCount: number | null;
  spanCount: number | null;
  readyForComparison: boolean;
  createdAt: string;
}

/** A version of a standard, as offered for selection. */
export interface StandardVersionSummary {
  id: string;
  version: string;
  lifecycle: string;
  indexingState: string;
  indexingMessage: string | null;
  /** Structural problems found during extraction (§12.8), for the document owner to fix. */
  extractionIssues: readonly string[];
  candidateCount: number;
  rejectedRequirementCount: number;
  /**
   * The extraction run whose requirements this version currently uses.
   *
   * Needed to address a requirement set — condition governance is scoped to
   * (standardVersionId, requirementSetVersion), because re-extracting produces a NEW set rather than
   * overwriting the old one, and a review decision belongs to the set it was made against.
   */
  requirementSetVersion: string | null;
  requirementCount: number;
  materialRequirementCount: number;
  /** True only when ACTIVE and INDEXED (§8.1) — the only versions worth offering. */
  comparable: boolean;
}

/** A standard and its versions (§8.1). */
export interface StandardSummary {
  id: string;
  standardKey: string;
  title: string;
  description: string | null;
  owner: string | null;
  category: string | null;
  /**
   * The three ranking dimensions for standard selection (§15.1).
   *
   * <p>MongoDB is their source of truth; the search index is rebuilt from them. Null is the common
   * case on an unlabelled corpus, and while all three are null the ranking signals do nothing at all —
   * which is why the admin list shows "not set" rather than omitting them.
   *
   * <p>`businessUnit` IS the department dimension; it keeps its original name because renaming it
   * would mean two fields to read and eventually two answers.
   */
  businessUnit: string | null;
  standardFamily: string | null;
  documentType: string | null;
  /**
   * Topics for this standard. May be derived from the document at index time rather than typed by a
   * human, which is why they are shown — an inferred value nobody can see is one nobody can correct.
   */
  tags: readonly string[];
  versions: readonly StandardVersionSummary[];
}

/** History row. Deliberately small — a list must not carry every finding. */
export interface ComparisonSummary {
  publicId: string;
  documentVersionId: string;
  filename: string;
  status: string;
  findingCount: number;
  actionableFindingCount: number;
  /** Top-ranked standard, for the history list. Null when none resolved (§15.1). */
  topStandardTitle: string | null;
  topStandardKey: string | null;
  /** Who ran the review, as the authenticated email. Null for comparisons stored before it was
   *  returned, so the column renders a dash rather than "undefined". */
  reviewer: string | null;
  createdAt: string;
  completedAt: string | null;
}

/**
 * Terminal comparison states (§21).
 *
 * Exported so polling logic has one definition of "stop asking". PARTIALLY_COMPLETED is terminal:
 * §18.4 makes it a real outcome with real findings, not a state that will resolve on its own.
 */
export const TERMINAL_COMPARISON_STATES: readonly string[] = [
  "COMPLETED",
  "PARTIALLY_COMPLETED",
  "FAILED",
];

/**
 * Whether requirements were ATTEMPTED and rejected, rather than simply not reached yet.
 *
 * <p>Only ever true for a finished comparison. Mid-run, `evaluated < total` means the remaining
 * requirements are still queued — announcing "36 of 52 could not be evaluated" at that point tells a
 * reviewer their review has failed when it is 16 requirements in and progressing normally.
 *
 * <p>This distinction only became necessary once progress was reported live: before that the counters
 * were null during a run, so the comparison could not be described as incomplete while it was still
 * working. §18.4 requires a genuinely partial review to say so — and equally requires that a review
 * in progress is not mistaken for one.
 */
export function isIncompleteReview(detail: {
  status: string;
  evaluatedRequirements?: number | null;
  totalRequirements?: number | null;
}): boolean {
  if (!TERMINAL_COMPARISON_STATES.includes(detail.status)) {
    return false;
  }
  const total = detail.totalRequirements ?? 0;
  const evaluated = detail.evaluatedRequirements ?? 0;
  return total > 0 && evaluated < total;
}


/**
 * Reader-facing label for each in-progress state.
 *
 * <p>The raw enum name is not a message. `SEARCHING_STANDARDS` rendered as "searching standards",
 * which reads as though the tool is looking for the standard rather than working through the review.
 *
 * <p>Labels ONLY, deliberately. These used to carry a percentage per stage, which was invented: a
 * stage's position in the sequence says nothing about elapsed or remaining work, and because the
 * worker does not report stage changes mid-run the number would sit unchanged for minutes while the
 * run advanced. Evaluation is the one stage with a real denominator, so it is the only one that
 * yields a percentage.
 */
export const COMPARISON_PROGRESS: Record<string, { label: string }> = {
  UPLOADED: { label: "Queued" },
  PARSING_DOCUMENT: { label: "Reading the document" },
  RENDERING_DOCUMENT: { label: "Preparing the document" },
  GENERATING_SPANS: { label: "Locating citable passages" },
  GENERATING_CHUNKS: { label: "Preparing passages for retrieval" },
  GENERATING_EMBEDDINGS: { label: "Indexing passages" },
  SEARCHING_STANDARDS: { label: "Selecting the relevant standards" },
  RETRIEVING_REQUIREMENT_EVIDENCE: { label: "Gathering evidence" },
  EVALUATING_REQUIREMENT: { label: "Comparing requirements" },
  AGGREGATING_CONTROLS: { label: "Rolling up controls" },
  GENERATING_FINDINGS: { label: "Writing findings" },
};

/**
 * Progress for a comparison in flight.
 *
 * <p>Returns `percent: null` until evaluation reports counts. The caller must then show an
 * indeterminate indicator: there is a real difference between "working, duration unknown" and a
 * number, and collapsing the first into the second is what made the bar look stuck at 62%.
 */
export function comparisonProgress(detail: {
  status: string;
  evaluatedRequirements?: number | null;
  totalRequirements?: number | null;
}): { label: string; percent: number | null } {
  const stage = COMPARISON_PROGRESS[detail.status] ?? { label: "Working" };

  const total = detail.totalRequirements ?? 0;
  const done = detail.evaluatedRequirements ?? 0;

  // A percentage ONLY when one has actually been measured.
  //
  // The worker advances its own stage locally and only reports back on a terminal callback, so
  // review-core keeps serving the state it had at dispatch — the bar sat at 62% for an entire run
  // while 118 of 120 requirements were evaluated. The per-stage percentages are invented constants:
  // they say nothing about how far along the run is, and a bar that stops moving reads as a hung job.
  //
  // So the ratio is reported when evaluation has started, and NOTHING is reported before that. A
  // missing number is honest about not knowing; a made-up one is not, and it is worse here than no
  // number at all because it invites the reader to estimate a finish time from it.
  if (total > 0 && done > 0) {
    return {
      label: done < total ? "Comparing requirements" : stage.label,
      // Floor, and capped at 99 until the run actually finishes. Math.round sends 199 of 200 to
      // 100%, and a bar reading 100% on a job that is still working is the same lie as a made-up
      // number: the reader concludes it is done and goes looking for results that do not exist yet.
      percent: done < total ? Math.min(99, Math.floor((done / total) * 100)) : 100,
    };
  }
  return { label: stage.label, percent: null };
}
