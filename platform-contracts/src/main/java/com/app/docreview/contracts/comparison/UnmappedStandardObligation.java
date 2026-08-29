package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ObligationType;

import java.util.List;

/**
 * A material obligation the standard states and no extracted atomic clause represents. §18.3, §18.6.
 *
 * <p>This is the evidence that makes {@link com.app.docreview.contracts.enums.EvaluationLimitation
 * #STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE} reviewable rather than an assertion. Without it the
 * limitation would be an unfalsifiable escape hatch: a model could claim an extraction gap whenever a
 * comparison was hard, and neither a reviewer nor a validator could tell a real gap from an excuse.
 *
 * <p><strong>{@code exactText} is verbatim, and it is checked.</strong> §18.2 validation requires the
 * text to occur in one of the cited standard spans, which are themselves required to have been
 * supplied and to belong to the requirement's provenance. So the obligation is anchored to the
 * authoritative source in the same way every other citation in the system is — the model reports where
 * it saw the obligation, and storage confirms the obligation is there.
 *
 * @param exactText        the obligation as the standard states it, verbatim
 * @param standardSpanIds  the supplied standard spans containing {@code exactText}
 * @param obligationType   what kind of obligation was dropped, for triage of the extraction defect
 * @param reason           why this is unrepresented by the supplied clauses
 */
public record UnmappedStandardObligation(
        String exactText,
        List<String> standardSpanIds,
        ObligationType obligationType,
        String reason
) {

    /** Span ids, never null, so callers need not null-check before iterating. */
    public List<String> standardSpanIds() {
        return standardSpanIds == null ? List.of() : standardSpanIds;
    }
}
