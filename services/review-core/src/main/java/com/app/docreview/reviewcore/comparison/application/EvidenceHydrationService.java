package com.app.docreview.reviewcore.comparison.application;

import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.FindingEvidenceResponse;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.HydratedEvidence;
import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads displayed evidence from stored spans. Architecture §16.5, §19.1, §39 rule 24.
 *
 * <p><strong>This class is the reason a quotation in the UI can be trusted.</strong> The comparison
 * model returns span IDs and nothing else — no text, no page numbers (see
 * {@code ComparisonModelOutput}). Every character the reviewer reads is fetched here from the
 * immutable span that {@code document-processing} extracted. A model cannot fabricate a quotation
 * because it never supplies one.
 *
 * <p>Spans are owned by {@code document-processing} (§22.2) and read over its internal API — never by
 * querying its database, which §22 forbids.
 *
 * <p>A span ID that does not resolve is DROPPED rather than rendered as empty or as the model's own
 * words. §18.2 already validated ownership before persistence, so an unresolvable ID here means the
 * span was deleted by retention or the document version was replaced. Showing a blank quotation would
 * look like a rendering bug; showing model text would defeat the entire mechanism.
 */
@Service
@Slf4j
public class EvidenceHydrationService {

    private final RestClient documentProcessing;

    public EvidenceHydrationService(JobDispatchProperties properties,
                                    InternalRestClientFactory clientFactory) {
        this.documentProcessing = clientFactory.create(
                properties.documentProcessing().baseUrl());
    }

    /**
     * Hydrates a finding's evidence for side-by-side display.
     *
     * @param standardVersionId the version owning the standard spans; enforced so evidence cannot be
     *                          pulled from a different standard than the finding names
     */
    public FindingEvidenceResponse hydrate(Finding finding, String documentVersionId) {
        return new FindingEvidenceResponse(
                finding.findingId(),
                finding.requirementId(),
                load(finding.standardVersionId(), finding.standardSpanIds()),
                load(documentVersionId, finding.processSpanIds()),
                // Kept separate so the viewer cannot present "closest related passage" as proof.
                load(documentVersionId, finding.nearestProcessSpanIds()));
    }

    /**
     * Fetches spans by ID from document-processing.
     *
     * <p>Returns an empty list rather than throwing when the call fails: a viewer that shows the
     * finding's reason and status but no highlight is degraded, whereas one that 500s shows nothing
     * at all. The failure is logged with counts so it is visible in operations.
     */
    private List<HydratedEvidence> load(String documentVersionId, List<String> spanIds) {
        if (documentVersionId == null || spanIds == null || spanIds.isEmpty()) {
            return List.of();
        }

        try {
            var spans = documentProcessing.post()
                    .uri("/internal/documents/{versionId}/spans", documentVersionId)
                    .body(Map.of("spanIds", spanIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<EvidenceSpan>>() {});

            if (spans == null) {
                return List.of();
            }

            if (spans.size() != spanIds.size()) {
                // Counts only — span text is confidential document content (§35).
                log.warn("Requested {} span(s) for {} but resolved {}; unresolved IDs are omitted "
                                + "rather than displayed empty",
                        spanIds.size(), documentVersionId, spans.size());
            }

            List<HydratedEvidence> hydrated = new ArrayList<>(spans.size());
            for (EvidenceSpan span : spans) {
                hydrated.add(new HydratedEvidence(
                        span.spanId(), span.page(),
                        span.sectionPath() == null ? List.of() : span.sectionPath(),
                        span.exactText(),
                        span.boundingBoxes() == null ? List.of() : span.boundingBoxes(),
                        // Node anchor for the HTML pane. Passed through unchanged for the same
                        // reason the geometry is: these were recorded by the parser that produced
                        // the text, and re-deriving them here would mean searching for the span's
                        // text in the document — the fuzzy re-location this design exists to avoid.
                        span.parentNodeId(),
                        span.charStart(),
                        span.charEnd(),
                        span.textHash(),
                        // Passed through, not recomputed: the producer recorded what it actually
                        // achieved, and re-deriving precision here from the box list would turn a
                        // paragraph-level fallback back into an apparently exact highlight.
                        span.highlightQuality(),
                        span.coordinateSpace(),
                        span.renditionSha256(),
                        span.pageGeometry()));
            }
            return hydrated;

        } catch (Exception e) {
            log.warn("Failed to hydrate {} span(s) for {}: {}",
                    spanIds.size(), documentVersionId, e.getClass().getSimpleName());
            return List.of();
        }
    }
}
