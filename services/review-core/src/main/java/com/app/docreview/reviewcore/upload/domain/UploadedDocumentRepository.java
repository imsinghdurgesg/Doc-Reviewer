package com.app.docreview.reviewcore.upload.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Uploaded process documents, owned by review-core (§22.1). */
public interface UploadedDocumentRepository extends MongoRepository<UploadedDocument, String> {

    Optional<UploadedDocument> findByDocumentVersionId(String documentVersionId);

    /**
     * Documents whose filename contains the term, case-insensitively.
     *
     * <p>Backs history search. Filename lives here rather than on the comparison, so the search
     * resolves ids here and the comparison query filters on them.
     */
    java.util.List<UploadedDocument> findByFilenameContainingIgnoreCase(String term);

    /** Backs duplicate detection: identical bytes must not become a second version (§19.2). */
    Optional<UploadedDocument> findByContentSha256(String contentSha256);

    List<UploadedDocument> findTop50ByOrderByCreatedAtDesc();
}
