package com.app.docreview.contracts.enums;

/**
 * Why a comparison could not produce a conclusion-eligible result. Architecture §18.3, §18.6.
 *
 * <p><strong>This names a limitation of the SYSTEM, not of the document.</strong> That distinction is
 * the reason the enum exists. A bare {@link ComparisonStatus#INSUFFICIENT_EVIDENCE} reads as "the
 * uploaded process did not provide enough evidence", which puts the burden on the reviewer to go
 * looking for something in their document. When the real cause is that our own extracted
 * representation of the standard was incomplete, that reading sends the reviewer to the wrong place
 * and quietly blames them for our defect.
 *
 * <p>A null limitation is the ordinary case: the status means exactly what it says.
 */
public enum EvaluationLimitation {

    /**
     * The standard states a material obligation that no extracted atomic clause represents.
     *
     * <p><strong>The failure this replaced.</strong> The validator requires a PARTIAL to name an
     * unsatisfied clause, so a reviewer can act on it. But when extraction drops an obligation — a
     * deadline, a frequency, an ordering constraint — the model can see the shortfall and has no clause
     * to attach it to. It answered PARTIAL with an empty unsatisfied list, validation rejected it, and
     * the retry re-asked a question with no admissible answer. CHG-08-R2 failed four times across
     * three retries; each attempt cost two model calls and produced nothing a reviewer could see.
     *
     * <p>Measured at 11 of 380 live material requirements, all carrying a timing obligation present in
     * the requirement title and absent from both the clause list and the normalized text.
     *
     * <p>A result carrying this limitation is TERMINAL and REVIEWABLE — it is not a retryable coverage
     * gap, because retrying cannot fix an extraction defect. It is excluded from the alignment
     * conclusion, because the obligation was never assessed.
     */
    STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE;

    /**
     * Whether a result carrying this limitation may count toward an alignment conclusion.
     *
     * <p>Always false today, and written as a method rather than assumed by callers so that adding a
     * limitation which IS conclusion-eligible is a deliberate decision at this line — not a silent
     * consequence of the enum gaining a value.
     */
    public boolean conclusionEligible() {
        return false;
    }

    /**
     * Whether this limitation always requires a human to look at the result.
     */
    public boolean requiresHumanReview() {
        return true;
    }
}
