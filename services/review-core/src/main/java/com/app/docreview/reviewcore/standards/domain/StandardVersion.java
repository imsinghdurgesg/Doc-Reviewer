package com.app.docreview.reviewcore.standards.domain;

import com.app.docreview.contracts.job.StandardIndexingState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An immutable revision of a standard. Architecture §2, §12.1, §19.2.
 *
 * <p><strong>Immutable means the CONTENT is immutable</strong> — the uploaded file, its hash, and
 * the requirement set extracted from it. Reopening a historical comparison must resolve the exact
 * standard, prompt, model, retrieval, and index versions originally used (§19.2), so a version's
 * content can never be edited in place. A revised standard is a NEW version.
 *
 * <p>Progress fields ({@code indexingState}, counts, timestamps) do change as indexing runs. That is
 * not a contradiction: they describe the processing of fixed content, not the content itself.
 *
 * <p>{@code contentSha256} is what makes re-upload detectable. Uploading a byte-identical file
 * should not silently create a second version and double every comparison against it.
 *
 * <p>{@code requirementSetVersion} pins WHICH extraction a comparison was measured against (§12.9).
 * Re-extraction creates a new requirement-set version; existing comparisons keep the old one, or
 * their findings would silently refer to requirements that no longer exist.
 */
@Document(collection = "standard_versions")
@CompoundIndex(name = "std_version_unique", def = "{'standardId': 1, 'version': 1}", unique = true)
@Getter
@NoArgsConstructor
public class StandardVersion {

    @Id
    private String id;

    @Indexed
    private String standardId;

    /** Version label as the organization writes it, e.g. {@code 2.4}. Immutable. */
    private String version;

    /** SHA-256 of the uploaded bytes. Immutable — it identifies the content. */
    private String contentSha256;

    /** MinIO key of the original upload: {@code active/{standardId}/{version}/source.ext} (§23). */
    private String sourceObjectKey;
    private String sourceFilename;
    private String sourceMediaType;
    private long sourceSizeBytes;

    @Setter private LocalDate effectiveDate;
    @Setter private LocalDate reviewDate;

    @Setter private StandardLifecycle lifecycle;
    @Setter private StandardIndexingState indexingState;
    /** Human-readable reason when indexing failed or needs review. Never raw document text (§35). */
    @Setter private String indexingMessage;

    /**
     * Structural problems found while extracting requirements (§12.8), for the administrator to fix
     * in the source document.
     *
     * <p>Persisted on the VERSION rather than left in the worker's own diagnostics, because the
     * person who must act on them is looking at the standards page — and §22 forbids review-core
     * reading another service's collections.
     */
    @Setter private java.util.List<String> extractionIssues;

    /** Candidate groups the detector found, so "7 sections -> 9 requirements" is showable. */
    @Setter private int candidateCount;

    /** Requirements rejected by validation. Non-zero is why a version is NEEDS_REVIEW. */
    @Setter private int rejectedRequirementCount;

    /** Rendered preview for the side-by-side viewer (§23). Null until rendered. */
    @Setter private String renderedObjectKey;
    /** Canonical extracted representation (§11.2). Null until parsed. */
    @Setter private String extractedObjectKey;

    /**
     * Identifies the requirement set this version currently publishes (§12.9). Comparisons record
     * the value they used so re-extraction cannot retroactively change a completed comparison.
     */
    @Setter private String requirementSetVersion;
    @Setter private int requirementCount;
    @Setter private int materialRequirementCount;
    @Setter private int spanCount;
    @Setter private int chunkCount;

    /** Embedding model alias and dimensions used to index this version (§10.5). */
    @Setter private String embeddingModelAlias;
    @Setter private Integer embeddingDimensions;

    private Instant createdAt;
    @Setter private Instant indexedAt;
    private String createdBy;

    public StandardVersion(String standardId, String version, String contentSha256,
                           String sourceObjectKey, String sourceFilename,
                           String sourceMediaType, long sourceSizeBytes, String createdBy) {
        this.standardId = standardId;
        this.version = version;
        this.contentSha256 = contentSha256;
        this.sourceObjectKey = sourceObjectKey;
        this.sourceFilename = sourceFilename;
        this.sourceMediaType = sourceMediaType;
        this.sourceSizeBytes = sourceSizeBytes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.lifecycle = StandardLifecycle.DRAFT;
        this.indexingState = StandardIndexingState.UPLOADED;
    }

    /**
     * True when this version may be used for a NEW comparison.
     *
     * <p>Both conditions are required. An ACTIVE version that is not INDEXED has no requirements to
     * compare against and would produce an empty result that looks like full compliance — which is
     * the most dangerous way for this system to be wrong.
     */
    public boolean comparable() {
        return lifecycle == StandardLifecycle.ACTIVE
                && indexingState == StandardIndexingState.INDEXED;
    }
}
