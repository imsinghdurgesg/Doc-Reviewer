package com.app.docreview.retrievalcomparison.extraction.web;

import com.app.docreview.retrievalcomparison.extraction.RequirementExtractionService;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs an extraction to MEASURE the extractor, never to publish one. Architecture §12.9, §19.2.
 *
 * <p><strong>What this is for.</strong> A review of 115 model-predicted scope gates reclassified 113.
 * The prompt was then changed to state the distinction it was missing — and the only honest way to know
 * whether that helped is to run it against the same conditions a person already judged, and score it.
 *
 * <p><strong>Why it cannot use the indexing pipeline.</strong> That pipeline extracts and then embeds
 * into the live standards corpus, deleting the previous vectors for the version. A shadow run there
 * would replace the reviewed set's vectors with an unreviewed extraction's — so this deliberately does
 * the extraction alone and stops. Nothing is embedded, nothing is indexed, no vector is deleted.
 *
 * <p><strong>Two independent guards, on purpose.</strong> The set is saved as
 * {@code ValidationStatus.SHADOW}, which the comparison selection query excludes; and this endpoint
 * never calls the indexing service at all. Either alone would be enough today, which is exactly why
 * both are here — the failure this prevents is silent, and the cost of redundancy is nothing.
 *
 * <p>Synchronous rather than a job: an evaluation run is an operator action whose result is read
 * immediately, and routing it through the job queue would make it indistinguishable in the job table
 * from a real indexing run.
 */
@RestController
@RequestMapping("/internal/shadow-extraction")
@RequiredArgsConstructor
@Slf4j
public class ShadowExtractionController {

    private final RequirementExtractionService extractionService;
    private final EvidenceSpanReader spanReader;

    /** What the run produced, enough to locate the set and sanity-check it before scoring. */
    public record ShadowRunResult(
            String standardVersionId,
            String requirementSetVersion,
            String promptVersion,
            String validationStatus,
            int candidateCount,
            int requirementCount,
            int materialRequirementCount,
            int rejectedCount) {
    }

    /**
     * Extracts a shadow set for one standard version.
     *
     * @param chunkingStrategyVersion recorded on the set, because chunking affects which candidates
     *                                exist at all — a run under different chunking is not comparable to
     *                                the reviewed one (§12.9), and the score would be meaningless.
     */
    @PostMapping("/{standardVersionId}")
    public ResponseEntity<ShadowRunResult> run(
            @PathVariable String standardVersionId,
            @RequestParam(defaultValue = "SEMANTIC-V2") String chunkingStrategyVersion,
            @RequestParam(required = false) String standardId,
            @RequestParam(required = false) String standardTitle) {

        var spans = spanReader.allForVersion(standardVersionId);
        if (spans.isEmpty()) {
            // Not an error to log loudly: a version with no spans has not been processed, and there is
            // nothing to extract from. Better a clear 409 than an empty set that scores as 0% recall.
            log.warn("Shadow extraction for {} found no spans; nothing to extract", standardVersionId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        var run = extractionService.extractShadow(
                standardId, standardVersionId,
                standardTitle == null ? "" : standardTitle,
                spans, chunkingStrategyVersion);

        // Counts and versions only — never requirement or condition text (§35).
        log.info("Shadow extraction {} for {}: {} candidates -> {} requirements, status {}",
                run.getRequirementSetVersion(), standardVersionId,
                run.getCandidateCount(), run.getRequirementCount(), run.getValidationStatus());

        return ResponseEntity.ok(new ShadowRunResult(
                standardVersionId,
                run.getRequirementSetVersion(),
                run.getRequirementExtractionPromptVersion(),
                String.valueOf(run.getValidationStatus()),
                run.getCandidateCount(),
                run.getRequirementCount(),
                run.getMaterialRequirementCount(),
                run.getRejectedCount()));
    }
}
