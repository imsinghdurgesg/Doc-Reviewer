package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.document.CanonicalDocument;
import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Persists canonical artifacts. Architecture §22.2, §23, §11.
 *
 * <p>Split by storage medium for the reason §22 gives: the canonical document is a versioned JSON
 * artifact and belongs in MinIO, while spans and chunks are queried by ID and belong in MongoDB.
 * Putting the JSON in Mongo would make every metadata query carry the document body.
 *
 * <p><strong>Writes are idempotent by document version.</strong> Existing spans and chunks for the
 * version are removed before insert, so a re-run after a recovered job produces one set of artifacts
 * rather than duplicates (§21, §39 rule 23). Span IDs are deterministic (see {@code SpanIdGenerator}),
 * so a re-run regenerates the same IDs and nothing that referenced them dangles.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CanonicalArtifactWriter {

    static final String SPANS_COLLECTION = "document_spans";
    static final String CHUNKS_COLLECTION = "document_chunks";

    /** §23 bucket layout. Standards and user documents are separate buckets. */
    static final String STANDARDS_BUCKET = "docreview-standards";
    static final String USER_DOCUMENTS_BUCKET = "docreview-user-documents";

    private final MongoTemplate mongo;
    private final com.app.docreview.documentprocessing.storage.ObjectStorage storage;

    /**
     * Owned rather than injected.
     *
     * <p>Boot 4 moved Jackson's autoconfiguration into a separate module that the web starter does
     * not pull in, so there is no {@code ObjectMapper} bean to inject and the context fails to start
     * with "no qualifying bean of type ObjectMapper".
     *
     * <p>Constructing one here is also the more correct choice: this mapper serializes a canonical
     * artifact whose JSON is a stored, versioned format (§11.2). Sharing a globally-configured
     * mapper would let an unrelated serialization tweak silently change the shape of artifacts that
     * existing comparisons still resolve against.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Writes the canonical document, its spans, and its chunks.
     *
     * @param documentType STANDARD or PROCESS; selects the bucket per §23
     */
    public void write(CanonicalDocument canonical, List<EvidenceSpan> spans,
                      List<SemanticChunk> chunks, String documentType) {

        String versionId = canonical.documentVersionId();

        // Replace rather than append. See the class comment on idempotency.
        var byVersion = Query.query(Criteria.where("documentVersionId").is(versionId));
        mongo.remove(byVersion, SPANS_COLLECTION);
        mongo.remove(byVersion, CHUNKS_COLLECTION);

        if (!spans.isEmpty()) {
            mongo.insert(spans, SPANS_COLLECTION);
        }
        if (!chunks.isEmpty()) {
            mongo.insert(chunks, CHUNKS_COLLECTION);
        }

        String bucket = "STANDARD".equals(documentType) ? STANDARDS_BUCKET : USER_DOCUMENTS_BUCKET;
        String key = "extracted/%s/document.json".formatted(versionId);
        storage.put(bucket, key, toJson(canonical), "application/json");

        log.info("Wrote artifacts for {}: {} spans, {} chunks, canonical at {}/{}",
                versionId, spans.size(), chunks.size(), bucket, key);
    }

    /**
     * Stores a rendered PDF preview and returns its key, or null when there is nothing to store.
     *
     * <p>Keyed {@code rendered/{versionId}/preview.pdf}, matching §23 and review-core's
     * {@code StorageBuckets.documentRendered}. The two must agree: the preview endpoint reads the key
     * review-core persisted, so a mismatch produces a 404 for a preview that was in fact created.
     *
     * <p>Returns null rather than throwing on an absent render. A preview is a convenience — evidence
     * spans come from the deterministic parse (§11.3) and stay valid without one — so a rendering
     * failure must not fail the processing job.
     */
    public String writeRenderedPreview(String documentVersionId, String documentType,
                                       java.util.Optional<byte[]> pdf) {
        if (pdf.isEmpty()) {
            log.info("No rendered preview stored for {}; the document remains fully comparable",
                    documentVersionId);
            return null;
        }
        String bucket = "STANDARD".equals(documentType) ? STANDARDS_BUCKET : USER_DOCUMENTS_BUCKET;
        String key = "rendered/%s/preview.pdf".formatted(documentVersionId);
        storage.put(bucket, key, pdf.get(), "application/pdf");
        log.info("Stored rendered preview for {} at {}/{}", documentVersionId, bucket, key);
        return key;
    }

    private byte[] toJson(CanonicalDocument canonical) {
        try {
            return objectMapper.writeValueAsBytes(canonical);
        } catch (Exception e) {
            // Never include the canonical object in the message: it holds the document text (§35).
            throw new IllegalStateException(
                    "Failed to serialize canonical document " + canonical.documentVersionId(), e);
        }
    }
}
