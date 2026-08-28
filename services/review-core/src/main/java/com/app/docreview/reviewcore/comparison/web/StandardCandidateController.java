package com.app.docreview.reviewcore.comparison.web;

import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Recommended standards for an uploaded document. Architecture §15.1, §6.
 *
 * <p><strong>Why the browser needs this.</strong> Selection used to happen only inside a comparison,
 * so a reviewer could not see what would be compared before committing to it. In REVIEW mode the
 * assessment must not begin until the reviewer has seen the candidates and chosen — which requires
 * asking "which standards apply?" as a question in its own right.
 *
 * <p>Proxied from retrieval-comparison because the vectors live there and §6 forbids depending on
 * another service's jar. The alternative — duplicating selection in review-core — would let the
 * recommendation drift from what an assessment actually uses, which is worse than an extra hop.
 *
 * <p><strong>Relevance is not compliance.</strong> A high score means "worth comparing against", never
 * "complies with": two passages can be near-identical and contradict each other outright. The response
 * carries scores so a reviewer can judge, not so the number can stand in for a verdict.
 */
@RestController
@RequestMapping("/api/comparisons/candidates")
@PreAuthorize("hasRole('REVIEWER')")
@Slf4j
public class StandardCandidateController {

    private final RestClient retrievalComparison;

    public StandardCandidateController(JobDispatchProperties properties,
                                       InternalRestClientFactory clientFactory) {
        this.retrievalComparison =
                clientFactory.create(properties.retrievalComparison().baseUrl());
    }

    /**
     * @param limit      how many candidates to consider. More than will be preselected: the point of
     *                   the screen is that a reviewer can see and include a lower-ranked standard.
     * @param department inferred or reviewer-corrected. A RANKING signal only — never a filter, so a
     *                   wrong inference cannot hide a standard family (§15.1).
     * @param guidance   free text, appended to the retrieval profile so a stated focus steers
     *                   retrieval that the document's own wording would not
     */
    @GetMapping("/{documentVersionId}")
    public Map<String, Object> candidates(
            @PathVariable String documentVersionId,
            @RequestParam(defaultValue = "8") int limit,
            @RequestParam(defaultValue = "") String department,
            @RequestParam(defaultValue = "") String standardFamily,
            @RequestParam(defaultValue = "") String documentType,
            @RequestParam(defaultValue = "") String guidance) {

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = retrievalComparison.get()
                    .uri(builder -> builder.path("/internal/v1/standard-candidates/{id}")
                            .queryParam("limit", limit)
                            .queryParam("department", department)
                            .queryParam("standardFamily", standardFamily)
                            .queryParam("documentType", documentType)
                            .queryParam("guidance", guidance)
                            .build(documentVersionId))
                    .retrieve()
                    .body(Map.class);
            return body == null ? empty(documentVersionId) : body;

        } catch (Exception e) {
            // Class name only (§35). An empty candidate list with a message beats a 500: the reviewer
            // can still search for a standard by hand, which is the point of the manual path.
            log.warn("Could not read standard candidates for {} ({})",
                    documentVersionId, e.getClass().getSimpleName());
            return Map.of(
                    "documentVersionId", documentVersionId,
                    "candidates", List.of(),
                    "inferred", noInference(),
                    "message", "Recommendations are unavailable. You can still search for a "
                            + "standard and start the comparison.");
        }
    }

    private static Map<String, Object> empty(String documentVersionId) {
        return Map.of("documentVersionId", documentVersionId,
                "candidates", List.of(),
                "inferred", noInference());
    }

    /**
     * A NONE-tier inference, so every response has the same shape.
     *
     * <p>The browser could tolerate an absent field, but then "the worker did not answer" and "the
     * worker inferred nothing" would look identical, and only one of them means retrieval is degraded.
     */
    private static Map<String, Object> noInference() {
        var inferred = new java.util.LinkedHashMap<String, Object>();
        // LinkedHashMap, not Map.of: nulls are the point here and Map.of rejects them.
        inferred.put("department", null);
        inferred.put("standardFamily", null);
        inferred.put("documentType", null);
        inferred.put("confidence", 0.0);
        inferred.put("tier", "NONE");
        return inferred;
    }
}
