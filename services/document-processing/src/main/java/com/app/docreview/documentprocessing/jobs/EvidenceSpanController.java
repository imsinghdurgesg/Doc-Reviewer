package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves evidence spans to other services. Architecture §9.8, §22.2.
 *
 * <p>This is the read side of the span-ownership boundary. Spans live in the {@code document_processing}
 * database, and §22 forbids another service querying them directly — retrieval-comparison asks here
 * instead. That indirection is what allows span storage to change without touching two services.
 *
 * <p>Two operations with deliberately different shapes:
 *
 * <ul>
 *   <li>{@code GET .../spans} returns the whole version, for requirement-candidate detection which
 *       scans the entire document (§12.2).</li>
 *   <li>{@code POST .../spans} takes an ID list, for evidence hydration which needs specific spans
 *       (§16.5). A POST because an ID list can exceed a practical URL length.</li>
 * </ul>
 *
 * <p>Both are filtered by {@code documentVersionId}, so a caller cannot read spans from a version it
 * did not ask for. §18.2 requires evidence ownership to be verifiable, and it starts here.
 *
 * <p>Reachable only with the internal service token (see {@link InternalTokenFilter}).
 */
@RestController
@RequestMapping("/internal/documents")
@RequiredArgsConstructor
@Slf4j
public class EvidenceSpanController {

    private final MongoTemplate mongo;

    /**
     * All semantic chunks of a version. Architecture §11.4, §20.2.
     *
     * <p>Chunks are the RETRIEVAL unit (§13.2) while spans are the citation unit, so the comparison
     * pipeline needs both: chunks to embed and search, spans to quote. They are served separately
     * because a chunk carries its own text and a caller embedding chunks has no use for the
     * span-level bounding boxes.
     */
    @GetMapping("/{documentVersionId}/chunks")
    public List<com.app.docreview.contracts.document.SemanticChunk> allChunks(
            @PathVariable String documentVersionId) {
        var query = Query.query(Criteria.where("documentVersionId").is(documentVersionId));
        var chunks = mongo.find(query, com.app.docreview.contracts.document.SemanticChunk.class,
                CanonicalArtifactWriter.CHUNKS_COLLECTION);
        log.debug("Returned {} chunk(s) for {}", chunks.size(), documentVersionId);
        return chunks;
    }

    /**
     * Node start offsets in the document's character stream, keyed by {@code nodeId}.
     *
     * <p>Serves the HTML review pane (§19.1). Spans carry DOCUMENT-ABSOLUTE ranges — {@code SpanBuilder}
     * computes {@code spanStart = nodeStart + rangeWithinNode} — so a viewer holding a node cannot turn
     * a span's range into a node-relative one without knowing where that node begins.
     *
     * <p><strong>Derived from the spans, not recomputed.</strong> The offsets were produced during
     * parsing by locating each node's text in the raw extracted text, and that raw text is not part of
     * the stored canonical artifact. Re-deriving it in another service would mean re-running a
     * normalisation-sensitive search against different input and getting offsets that disagree with the
     * ones the spans already use — highlights would land near, but not on, the cited sentence.
     *
     * <p><strong>Each span's position WITHIN its node is subtracted, rather than taking the minimum
     * span offset.</strong> The minimum is only the node's start when the node's first sentence
     * survived span generation, and it often does not: {@code SpanBuilder} drops any sentence below
     * {@code MIN_SPAN_CHARS}, so a node beginning {@code "Scope. The requirements apply..."} has its
     * first citable span seven characters in. Using the minimum there reports the node as starting
     * seven characters late, and every highlight in it is shifted by exactly that much — far enough to
     * cut a word, close enough to look like a rendering quirk rather than an offset bug.
     *
     * <p>Nodes with no citable span are absent from the map. The viewer renders them as ordinary text
     * and simply has nothing to highlight there, which is correct: no span means no evidence can point
     * at that node.
     */
    @GetMapping("/{documentVersionId}/node-offsets")
    public Map<String, NodeAnchor> nodeOffsets(@PathVariable String documentVersionId) {
        var byVersion = Query.query(Criteria.where("documentVersionId").is(documentVersionId));
        var spans = mongo.find(byVersion, EvidenceSpan.class,
                CanonicalArtifactWriter.SPANS_COLLECTION);

        Map<String, NodeAnchor> anchors = new java.util.HashMap<>();
        for (EvidenceSpan span : spans) {
            if (span.parentNodeId() == null || span.charStart() == null
                    || span.exactText() == null) {
                continue;
            }
            // The caller holds the node text and can locate this span's text inside it; the pair
            // (absolute start, the exact text at that position) is everything needed to solve for
            // the node's own start without this service loading the canonical artifact.
            anchors.merge(span.parentNodeId(),
                    new NodeAnchor(span.charStart(), span.exactText().strip()),
                    (a, b) -> a.charStart() <= b.charStart() ? a : b);
        }

        log.debug("Returned {} node anchor(s) for {}", anchors.size(), documentVersionId);
        return anchors;
    }

    /**
     * The earliest span in a node, as an absolute position plus the text found there.
     *
     * <p>Enough for the caller to solve for the node's start: {@code nodeStart = charStart -
     * indexOf(text)}. Sending the pair rather than a computed offset keeps this service from having
     * to load the canonical artifact, which lives in object storage rather than Mongo.
     */
    public record NodeAnchor(int charStart, String text) {
    }

    /** All spans of a version, in reading order. */
    @GetMapping("/{documentVersionId}/spans")
    public List<EvidenceSpan> allSpans(@PathVariable String documentVersionId) {
        var query = Query.query(Criteria.where("documentVersionId").is(documentVersionId));
        var spans = mongo.find(query, EvidenceSpan.class,
                CanonicalArtifactWriter.SPANS_COLLECTION);
        log.debug("Returned {} span(s) for {}", spans.size(), documentVersionId);
        return spans;
    }

    /**
     * Specific spans by ID.
     *
     * <p>Missing IDs are simply absent from the result rather than an error, matching the port's
     * contract (§9.8): a stale reference should not fail a whole comparison.
     */
    @PostMapping("/{documentVersionId}/spans")
    public List<EvidenceSpan> spansByIds(@PathVariable String documentVersionId,
                                         @RequestBody Map<String, List<String>> body) {
        var spanIds = body.getOrDefault("spanIds", List.of());
        if (spanIds.isEmpty()) {
            return List.of();
        }

        // Both criteria, always. Filtering on spanId alone would return a span belonging to a
        // different document version if IDs ever collided across versions — exactly the
        // cross-version evidence leak §18.2 forbids.
        var query = Query.query(Criteria.where("documentVersionId").is(documentVersionId)
                .and("spanId").in(spanIds));
        var spans = mongo.find(query, EvidenceSpan.class,
                CanonicalArtifactWriter.SPANS_COLLECTION);

        if (spans.size() != spanIds.size()) {
            log.debug("Requested {} span(s) for {} but found {}",
                    spanIds.size(), documentVersionId, spans.size());
        }
        return spans;
    }
}
