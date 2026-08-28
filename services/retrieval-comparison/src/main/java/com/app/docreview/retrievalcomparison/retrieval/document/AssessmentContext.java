package com.app.docreview.retrievalcomparison.retrieval.document;

/**
 * What is known about the document being assessed, for ranking candidates. Architecture §15.1.
 *
 * <p><strong>Every field is a RANKING signal, never a filter.</strong> Excluding a standard because
 * its department does not match would hide a whole family behind metadata nobody has verified, and the
 * reviewer would see a clean result with no indication anything was withheld. §15.1 requires the
 * opposite: a standard that was considered and not used must be visible, with the reason.
 *
 * <p>Hard exclusion is reserved for facts rather than inferences — lifecycle status, repository
 * visibility, authorization, and an administrator's explicit curation. An inferred department is not a
 * fact.
 *
 * @param department   the document's owning department, inferred or confirmed by the reviewer
 * @param standardFamily the family expected to govern it
 * @param documentType what kind of document this is, e.g. {@code PROCUREMENT_PROCEDURE}
 * @param guidance     free text from the reviewer. Retained verbatim AND appended to the profile
 *                     text, because a stated focus ("emergency changes only") steers retrieval that
 *                     the document's own wording would not.
 */
public record AssessmentContext(
        String department,
        String standardFamily,
        String documentType,
        String guidance) {

    public static AssessmentContext none() {
        return new AssessmentContext(null, null, null, null);
    }

    public boolean isEmpty() {
        return blank(department) && blank(standardFamily) && blank(documentType) && blank(guidance);
    }

    /**
     * The multiplier to apply to a candidate's raw relevance.
     *
     * <p><strong>Bounded deliberately.</strong> At most +18% on a full match and -14% on a full
     * mismatch, giving a best/worst ratio of 1.37. That keeps a 0.20 gap in raw relevance decisive: if
     * metadata could dominate, an inference at 0.9 confidence would decide the assessment, which is
     * the failure soft ranking exists to avoid.
     */
    public double weight(String candidateDepartment, String candidateFamily,
                         String candidateDocumentType) {
        // ADDITIVE, then clamped — not three multiplied factors.
        //
        // Multiplying looked modest per dimension (±15%, ±10%, ±10%) and compounded to a best/worst
        // ratio of 2.02: a standard at 0.50 relevance with every label matching beat one at 0.70 with
        // every label wrong. That is metadata deciding the assessment, which is exactly what soft
        // ranking is supposed to avoid — and the failure is invisible per dimension, only appearing
        // when all three align.
        //
        // Summing and clamping the total makes the ceiling explicit and testable, rather than an
        // emergent product of per-dimension choices.
        double adjustment = 0.0;
        adjustment += delta(department, candidateDepartment, 0.10);
        adjustment += delta(standardFamily, candidateFamily, 0.06);
        adjustment += delta(documentType, candidateDocumentType, 0.06);

        // Clamped so the best case is at most 1.18x and the worst at least 0.86x. Ratio 1.37, which
        // keeps a 0.20 gap in raw relevance decisive — see the test that pins this.
        double clamped = Math.max(-0.14, Math.min(0.18, adjustment));
        return 1.0 + clamped;
    }

    /** {@code +magnitude} on a match, {@code -magnitude} on a mismatch, 0 when either side is absent. */
    private static double delta(String expected, String actual, double magnitude) {
        if (blank(expected) || blank(actual)) {
            return 0.0;
        }
        return expected.trim().equalsIgnoreCase(actual.trim()) ? magnitude : -magnitude;
    }


    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
