package com.app.docreview.contracts.enums;

/**
 * Whether a requirement APPLIES to a document. Architecture §12.5, §17.5, §17.6.
 *
 * <p><strong>Deliberately separate from {@link ComparisonStatus}.</strong> They answer different
 * questions, and collapsing them into one field produces a specific wrong answer:
 *
 * <ul>
 *   <li>Applicability asks <em>should this requirement be measured against this document at all?</em></li>
 *   <li>Comparison asks <em>having measured it, does the document satisfy it?</em></li>
 * </ul>
 *
 * <p>A single enum forces a requirement that does not apply into a compliance verdict. Every available
 * choice is wrong: {@code MISSING} reports a gap the standard never demanded, {@code ALIGNED} claims
 * compliance that was never assessed, and {@code INSUFFICIENT_EVIDENCE} says the evidence was weak when
 * in truth none was needed. "A change-management control has nothing to say about a data-retention
 * procedure" is not a finding on any of those scales.
 *
 * <p>So the two travel together on a finding and mean different things. A requirement that is
 * {@code NOT_APPLICABLE} has no comparison status at all — it was never compared.
 *
 * <p><strong>The default is {@link #APPLICABLE}, not "unknown".</strong> Most requirements in a standard
 * apply unconditionally; a standard that governs change management governs all of it. Defaulting to
 * anything weaker would mark the ordinary case as needing investigation, which both buries the genuine
 * exceptions and lets a silent classification failure quietly shrink the scope of a review.
 */
public enum ApplicabilityStatus {

    /**
     * The requirement applies and should be compared. THE DEFAULT.
     *
     * <p>Used for every requirement with no stated applicability condition — which is most of them.
     */
    APPLICABLE,

    /**
     * The requirement carries a condition, and the condition is not met by this document.
     *
     * <p>Excluded from comparison on STRUCTURAL grounds, not on evidence. This is the state that stops
     * an inapplicable control being reported as a gap.
     *
     * <p>Requires a recorded reason: an exclusion nobody can inspect is indistinguishable from a
     * requirement the platform simply lost, and it silently reduces what the review covered.
     */
    NOT_APPLICABLE,

    /**
     * The requirement carries a condition that could not be resolved either way.
     *
     * <p><strong>Still compared.</strong> This flags uncertainty about scope; it does not withhold the
     * requirement. Withholding on doubt would let an unclear condition remove a genuine obligation from
     * the review — the reviewer would see a clean result with no indication anything was skipped.
     *
     * <p>Distinct from {@code ComparisonStatus.INSUFFICIENT_EVIDENCE}, which is about the document's
     * evidence for a requirement that definitely applies. This is about whether it applies.
     */
    UNCERTAIN;

    /** True when the requirement should be evaluated. UNCERTAIN counts: doubt does not exclude. */
    public boolean shouldCompare() {
        return this != NOT_APPLICABLE;
    }

    /**
     * True when a human should look at the scope decision.
     *
     * <p>Both non-default states qualify, for opposite reasons: an exclusion removed a requirement from
     * the review, and an unclear condition means the platform could not tell whether it should have.
     */
    public boolean needsScopeReview() {
        return this != APPLICABLE;
    }
}
