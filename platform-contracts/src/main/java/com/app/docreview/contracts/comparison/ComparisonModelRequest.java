package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.retrieval.SearchCoverage;

import java.util.List;

/**
 * One requirement's comparison input. Architecture §16.2.
 *
 * <p>Deliberately narrow: ONE requirement against the passages retrieved for it. §15.5 forbids
 * placing a whole document and several standards in one prompt, and the reason is not only context
 * limits — a model asked about everything at once produces findings that cannot be attributed to
 * specific evidence, and a reviewer cannot check them.
 *
 * <p>{@code coverage} travels WITH the request so the model can distinguish "the document does not
 * require this" from "the document was not adequately searched" (§17.6). Without it the model has no
 * basis for choosing between MISSING and INSUFFICIENT_EVIDENCE and will guess.
 *
 * @param processEvidenceCandidates the ONLY process spans the model may cite. An ID outside this
 *                                  list fails validation (§18.2), which is what makes fabricated
 *                                  evidence impossible rather than merely discouraged.
 */
public record ComparisonModelRequest(
        String comparisonId,
        String documentVersionId,
        String standardVersionId,
        String requirementSetVersion,
        RequirementPayload requirement,
        List<EvidencePayload> standardEvidence,
        List<EvidencePayload> processEvidenceCandidates,
        SearchCoverage coverage
) {

    /**
     * @param sourceSpanIds the authoritative statement of the requirement (§12.5). Standard evidence
     *                      must include these, or the finding cites something other than the rule it
     *                      claims to apply (§18.2).
     */
    public record RequirementPayload(
            String requirementId,
            String title,
            String normalizedRequirement,
            List<AtomicClause> atomicClauses,
            List<String> sourceSpanIds
    ) {}

    /**
     * A span offered as evidence.
     *
     * <p>Text IS included here — the model cannot judge a passage it cannot read. The distinction
     * from §16.5 is what happens afterwards: the model returns only IDs, and displayed evidence is
     * re-loaded from storage rather than taken from the response.
     */
    public record EvidencePayload(
            String spanId,
            String section,
            Integer page,
            String text
    ) {}
}
