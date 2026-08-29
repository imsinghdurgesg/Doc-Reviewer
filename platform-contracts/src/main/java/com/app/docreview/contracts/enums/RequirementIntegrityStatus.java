package com.app.docreview.contracts.enums;

/**
 * Whether an extracted requirement is complete enough to compare. Architecture §12.7, §18.3.
 *
 * <p><strong>Why this is decided here and not by the comparison model.</strong> A withdrawn revision let
 * the comparison model report that our extraction was incomplete. Because the branch was reachable on
 * every requirement, the model used it whenever a comparison was hard — for ordinary evidence
 * uncertainty, for obligations a clause already covered, and for reasoning it could not map onto the
 * clause list. Across 191 requirements, evaluated fell from 187 to 46; two prompt revisions did not fix
 * it, because each misfire costs a whole control.
 *
 * <p>The model cannot answer that question. It sees one requirement and the passages retrieved for it —
 * not the extractor, not the provenance spans withheld from it, not the rest of the set. So the judgement
 * moves to ingestion, where it is deterministic, reviewable, and made ONCE per requirement rather than
 * re-litigated on every comparison.
 *
 * <p>The comparison model's job narrows to what it can actually do: judge the process against the
 * requirement it was given.
 */
public enum RequirementIntegrityStatus {

    /** Every material obligation in the source is represented in the clauses. The ordinary case. */
    COMPLETE,

    /**
     * The source states a material obligation that no atomic clause represents.
     *
     * <p>Measured on 11 of 380 live material requirements, all a timing or ordering constraint present
     * in the requirement title and its provenance spans but absent from both the clause list and the
     * normalized text. The extractor cited several source spans and built the requirement from one.
     *
     * <p>A requirement in this state is NOT sent to the comparison model. Asking it to evaluate a clause
     * list that omits the obligation produces an unanswerable question: it can see the shortfall and has
     * no clause to attach it to. Instead a deterministic extraction-review result is recorded, carrying
     * the exact missing obligation and its source spans.
     */
    INCOMPLETE_ATOMIC_COVERAGE,

    /**
     * Flagged for a human to inspect before it is used, for a reason other than missing clause coverage.
     *
     * <p>Also not comparison-eligible: an integrity question that nobody has resolved must not be
     * silently resolved by comparing anyway.
     */
    NEEDS_REVIEW;

    /**
     * Whether a requirement in this state may be sent to the comparison model.
     *
     * <p>A positive test on the single eligible state, so a status added later defaults to NOT eligible.
     * That is the safe direction: comparing a requirement whose integrity is unknown produces a verdict
     * that looks identical to a trustworthy one.
     */
    public boolean comparisonEligible() {
        return this == COMPLETE;
    }

    /** Whether this state requires a reviewer to see the requirement. */
    public boolean requiresHumanReview() {
        return this != COMPLETE;
    }
}
