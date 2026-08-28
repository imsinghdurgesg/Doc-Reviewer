package com.app.docreview.reviewcore.upload.domain;

import com.app.docreview.contracts.job.ComparisonState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An uploaded process document. Architecture §8.1, §14.
 *
 * <p>Versioned like a standard, for the same reason (§19.2): a completed comparison must resolve
 * exactly the bytes it measured. Re-uploading a revised document creates a new version rather than
 * replacing the old one, so an existing comparison never silently re-points at different content.
 *
 * <p>{@code contentSha256} is unique per document version and drives duplicate detection. A user who
 * double-submits a form gets the existing version back rather than a second parse and a second set of
 * spans.
 *
 * <p>Binary content is NOT here — it lives in MinIO (§22, §23). This record holds the reference.
 */
@Document(collection = "uploaded_documents")
@Getter
@NoArgsConstructor
public class UploadedDocument {

    @Id
    private String id;

    /**
     * The version identifier used by every downstream service and vector payload.
     *
     * <p>Distinct from {@code id} so it reads meaningfully in a Qdrant payload and a job log. Mongo
     * assigns {@code id}; this is assigned by the application and is what travels between services.
     */
    @Indexed(unique = true)
    private String documentVersionId;

    private String filename;
    private String mediaType;
    private long sizeBytes;

    @Indexed
    private String contentSha256;

    /** Bucket and key in MinIO (§23). */
    private String sourceBucket;
    private String sourceObjectKey;

    /** Processing progress shown to the user (§21). */
    @Setter private ComparisonState state;
    @Setter private String stateMessage;

    /** Populated by the document-processing callback. */
    @Setter private Integer pageCount;
    @Setter private Integer spanCount;
    @Setter private Integer chunkCount;
    @Setter private String chunkingStrategyVersion;
    @Setter private String renderedObjectKey;

    private String uploadedBy;
    private Instant createdAt;
    @Setter private Instant processedAt;

    public UploadedDocument(String documentVersionId, String filename, String mediaType,
                            long sizeBytes, String contentSha256, String sourceBucket,
                            String sourceObjectKey, String uploadedBy) {
        this.documentVersionId = documentVersionId;
        this.filename = filename;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.contentSha256 = contentSha256;
        this.sourceBucket = sourceBucket;
        this.sourceObjectKey = sourceObjectKey;
        this.uploadedBy = uploadedBy;
        this.state = ComparisonState.UPLOADED;
        this.createdAt = Instant.now();
    }

    /**
     * True when spans and chunks exist, so this document can be compared.
     *
     * <p>Gates comparison creation the same way §8.1 gates standard activation on INDEXED: comparing
     * against an unprocessed document would retrieve no evidence and report every requirement
     * MISSING, which a reviewer reads as total non-compliance rather than as "not ready yet".
     */
    public boolean readyForComparison() {
        return spanCount != null && spanCount > 0;
    }
}
