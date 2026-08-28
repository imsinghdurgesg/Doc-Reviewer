package com.app.docreview.contracts.retrieval;

import java.util.List;

/**
 * Evidence that the document was actually searched. Architecture §15.4.
 *
 * <p><strong>This is what earns the right to say MISSING.</strong> §17.6 draws the line between two
 * verdicts that look similar and mean opposite things:
 *
 * <ul>
 *   <li>{@code MISSING} — the document was adequately searched and the obligation is absent.</li>
 *   <li>{@code INSUFFICIENT_EVIDENCE} — we cannot tell. Parsing was incomplete, a referenced
 *       procedure was not supplied, or nothing came back above threshold.</li>
 * </ul>
 *
 * <p>Without this record the two are indistinguishable, and the system defaults to asserting a
 * compliance gap it never established. That is the most damaging error available to it: a reviewer
 * acts on a finding that was really a search failure.
 *
 * @param sectionsSearched which parts of the document were covered — a reviewer's first question
 *                         when a finding looks wrong is "did you even look in section 4?"
 * @param chunksSearched   how many passages were considered
 * @param closestScore     the best score seen even if below threshold. A near miss at 0.68 and
 *                         nothing at all at 0.02 justify different verdicts.
 * @param unresolvedExternalReferences procedures the document points at but did not include; each
 *                         one is a reason the answer may be INSUFFICIENT_EVIDENCE rather than MISSING
 * @param parseComplete    false when extraction was degraded — a scanned page with no text layer
 *                         means the obligation may be present and unreadable
 */
public record SearchCoverage(
        List<String> sectionsSearched,
        int chunksSearched,
        double thresholdApplied,
        Double closestScore,
        List<String> closestPassageSpanIds,
        List<String> unresolvedExternalReferences,
        boolean parseComplete,
        boolean documentClaimsDomain
) {

    /**
     * True when a MISSING verdict is defensible.
     *
     * <p>All three conditions are required. Any one of them failing means absence was not
     * established, only unobserved — and §17.6 requires INSUFFICIENT_EVIDENCE in that case.
     */
    public boolean supportsAbsenceVerdict() {
        return parseComplete
                && chunksSearched > 0
                && unresolvedExternalReferences.isEmpty();
    }
}
