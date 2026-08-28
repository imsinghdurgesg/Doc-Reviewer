package com.app.docreview.contracts.enums;

/**
 * Verdict for one requirement. Architecture §17.
 *
 * <p>Five values, not three. The two that are easy to conflate carry the most weight:
 *
 * <ul>
 *   <li>{@link #MISSING} — the document was adequately searched and the obligation is absent.</li>
 *   <li>{@link #INSUFFICIENT_EVIDENCE} — we cannot tell. Scope unclear, a referenced procedure was
 *       not supplied, or parsing was incomplete.</li>
 * </ul>
 *
 * <p>Reporting the second as the first asserts a compliance gap that was never established, which
 * is the most damaging error this system can make (§17.6). Status is deliberately independent of
 * severity: severity comes from standard metadata and administrator rules, never from the model
 * (§17.7).
 */
public enum ComparisonStatus {
    ALIGNED,
    PARTIAL,
    MISSING,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE
}
