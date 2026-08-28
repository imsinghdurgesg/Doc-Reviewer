package com.app.docreview.reviewcore.comparison.application;

import com.app.docreview.contracts.document.CanonicalDocument;
import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.reviewcore.comparison.web.dto.CanonicalViewDtos.CanonicalDocumentView;
import com.app.docreview.reviewcore.comparison.web.dto.CanonicalViewDtos.CanonicalNodeView;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Assembles the reflowable HTML view of a document version. Architecture §11.2, §19.1.
 *
 * <p>Reads the canonical artifact that {@code document-processing} wrote to object storage and joins
 * it with that service's node offsets, producing nodes a browser can render and highlight against.
 *
 * <p><strong>Offsets come from the owning service, never recomputed here.</strong> They were derived
 * while parsing, from raw extracted text that the canonical artifact does not retain. Recomputing
 * them from node text would re-run a normalisation-sensitive search against different input; the
 * result would disagree with the offsets the spans already carry, and every highlight would land near
 * its sentence rather than on it. §22 also forbids this service reaching into another's data — the
 * join happens over the internal API for both reasons.
 *
 * <p>Repeated content (running headers and footers) is dropped by default, matching §11.2 and
 * {@code SpanBuilder}, which never emits a citable span for a repeated node. Rendering it would show
 * a page header no finding can ever point at.
 */
@Service
@Slf4j
public class CanonicalViewService {

    private final ObjectStorage storage;
    private final RestClient documentProcessing;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CanonicalViewService(ObjectStorage storage,
                                JobDispatchProperties properties,
                                InternalRestClientFactory clientFactory) {
        this.storage = storage;
        this.documentProcessing = clientFactory.create(
                properties.documentProcessing().baseUrl());
    }

    /**
     * @param bucket            where the canonical artifact lives; STANDARDS or USER_DOCUMENTS per §23
     * @param includeRepeated   keep running headers and footers, which are excluded by default
     */
    public CanonicalDocumentView load(String bucket, String documentVersionId,
                                      boolean includeRepeated) {

        CanonicalDocument canonical = readCanonical(bucket, documentVersionId);
        Map<String, NodeAnchor> anchors = nodeAnchors(documentVersionId);

        List<CanonicalNode> nodes = canonical.nodes() == null ? List.of() : canonical.nodes();
        List<CanonicalNodeView> views = new ArrayList<>(nodes.size());

        for (CanonicalNode node : nodes) {
            if (node.repeated() && !includeRepeated) {
                continue;
            }
            String text = node.text() == null ? "" : node.text();

            // Absent from the map when the node produced no citable span — a heading below the span
            // floor, or an empty cell. It still renders; there is simply no evidence pointing at it,
            // so a highlight can never be requested against it and the offset is unused.
            Integer charStart = resolveNodeStart(anchors.get(node.nodeId()), text);

            views.add(new CanonicalNodeView(
                    node.nodeId(),
                    node.nodeType(),
                    node.sectionPath() == null ? List.of() : node.sectionPath(),
                    node.page(),
                    node.paragraph(),
                    node.ordinal(),
                    text,
                    node.textHash(),
                    charStart == null ? -1 : charStart,
                    charStart == null ? -1 : charStart + text.length()));
        }

        // Reading order, which is not list order once tables and multi-column layouts are involved
        // (§11.2). Sorting here rather than trusting the client keeps every consumer consistent.
        views.sort(Comparator.comparingInt(CanonicalNodeView::ordinal));

        long resolved = views.stream().filter(v -> v.charStart() >= 0).count();
        log.debug("Canonical view for {}: {} node(s) of {}, {} with resolved offsets",
                documentVersionId, views.size(), nodes.size(), resolved);

        return new CanonicalDocumentView(
                canonical.documentVersionId(),
                canonical.title(),
                canonical.contentHash(),
                canonical.pages(),
                views);
    }

    /**
     * Solves for a node's start offset from its earliest span.
     *
     * <p>{@code nodeStart = span.charStart - indexOf(span.text within the node)}. The subtraction is
     * the whole point: a span is NOT necessarily at the beginning of its node. {@code SpanBuilder}
     * drops any sentence below its minimum length, so a node beginning {@code "Scope. The
     * requirements apply..."} has its first citable span seven characters in. Treating that span's
     * offset as the node's start reports the node as beginning seven characters late, and every
     * highlight inside it is shifted by exactly that much — enough to cut a word off the front, small
     * enough to look like a rendering quirk rather than an arithmetic error.
     *
     * @return the node's start, or null when it cannot be established — in which case the view
     *         reports -1 and the client draws no highlight rather than a displaced one
     */
    private Integer resolveNodeStart(NodeAnchor anchor, String nodeText) {
        if (anchor == null || anchor.text() == null || nodeText.isEmpty()) {
            return null;
        }
        int within = nodeText.indexOf(anchor.text());
        if (within < 0) {
            // The span's text is not in the node the span names. A re-parse changed the text under
            // stored spans; claiming an offset here would place highlights confidently wrong.
            log.debug("Span text not found in node; offset unresolved");
            return null;
        }
        return anchor.charStart() - within;
    }

    private CanonicalDocument readCanonical(String bucket, String documentVersionId) {
        String key = "extracted/%s/document.json".formatted(documentVersionId);
        try (InputStream in = storage.stream(bucket, key)) {
            return objectMapper.readValue(in, CanonicalDocument.class);
        } catch (IOException | RuntimeException e) {
            // Absent when processing has not finished, or when parsing failed. A 404 states that
            // plainly; a 500 would read as a server fault for a document that is merely still being
            // processed. Never include the exception message — it can carry document content (§35).
            log.debug("No canonical artifact at {}/{}: {}", bucket, key, e.getClass().getSimpleName());
            throw ApiException.notFound("Canonical document");
        }
    }

    /**
     * Node start offsets from the owning service.
     *
     * <p>Returns an empty map rather than throwing when the call fails. The viewer then renders the
     * document with no highlights, which is degraded but readable and correctly labelled — every
     * span resolves to {@code UNRESOLVED} because no node offset is available. Failing the whole
     * request instead would blank a pane that can still show the reviewer their document.
     */
    private Map<String, NodeAnchor> nodeAnchors(String documentVersionId) {
        try {
            var anchors = documentProcessing.get()
                    .uri("/internal/documents/{versionId}/node-offsets", documentVersionId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, NodeAnchor>>() {});
            return anchors == null ? Map.of() : anchors;
        } catch (Exception e) {
            log.warn("Failed to load node anchors for {}: {}; HTML highlights will be unresolved",
                    documentVersionId, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** Mirrors {@code EvidenceSpanController.NodeAnchor}; see {@link #resolveNodeStart}. */
    public record NodeAnchor(int charStart, String text) {
    }
}
