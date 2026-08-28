package com.app.docreview.retrievalcomparison.llm;

import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.contracts.requirement.ExtractedRequirement;

import java.util.List;

/**
 * Port for requirement extraction. Architecture §9.4.
 *
 * <p>An interface so domain code never sees Spring AI (§10.3). Only
 * {@code llm.litellm.LiteLlmRequirementExtractionModelGateway} knows the provider exists.
 */
public interface RequirementExtractionModelGateway {

    /**
     * Extracts requirements from one candidate group.
     *
     * <p>One call per candidate, not per document: §12.4 supplies only the candidate's spans, and a
     * whole-document prompt both exceeds context limits and lets the model invent requirements from
     * regions it was not asked about.
     *
     * @param allowedSpanIds the ONLY span IDs the model may cite. Anything else is rejected by
     *                       validation (§12.8 rule 2) — this is what makes fabricated evidence
     *                       structurally impossible rather than merely discouraged.
     */
    Result extract(RequirementCandidate candidate, List<String> allowedSpanIds, Context context);

    /**
     * @param standardVersionId pinned so extraction cannot silently attribute a requirement to a
     *                          different version
     */
    record Context(
            String standardId,
            String standardVersionId,
            String requirementSetVersion,
            String standardTitle
    ) {}

    /**
     * @param requirements      what the model returned, before validation
     * @param modelAlias        recorded per §10.4 and persisted with the requirement set (§13.1)
     * @param promptTokens      diagnostics; §10.4 requires token usage to be recorded
     * @param rawResponseValid  false when the response was not parseable against the schema, so the
     *                          caller can retry once with the errors (§12.8)
     */
    record Result(
            List<ExtractedRequirement> requirements,
            String modelAlias,
            String promptVersion,
            String schemaVersion,
            int promptTokens,
            int completionTokens,
            long latencyMillis,
            boolean rawResponseValid,
            List<String> validationErrors
    ) {}
}
