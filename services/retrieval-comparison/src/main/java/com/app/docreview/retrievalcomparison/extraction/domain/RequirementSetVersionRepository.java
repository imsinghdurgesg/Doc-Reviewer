package com.app.docreview.retrievalcomparison.extraction.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Extraction runs (§12.9). */
public interface RequirementSetVersionRepository
        extends MongoRepository<RequirementSetVersionDocument, String> {

    List<RequirementSetVersionDocument> findByStandardVersionIdOrderByCreatedAtDesc(
            String standardVersionId);

    Optional<RequirementSetVersionDocument> findFirstByStandardVersionIdAndValidationStatusOrderByCreatedAtDesc(
            String standardVersionId, StandardRequirementDocument.ValidationStatus status);
}
