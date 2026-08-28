package com.app.docreview.retrievalcomparison.extraction.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Published requirements, owned by retrieval-comparison (§22.3). */
public interface StandardRequirementRepository
        extends MongoRepository<StandardRequirementDocument, String> {

    List<StandardRequirementDocument> findByStandardVersionIdAndRequirementSetVersion(
            String standardVersionId, String requirementSetVersion);

    /**
     * The set a comparison actually evaluates. Filtering in the query rather than in memory matters
     * once a standard has hundreds of requirements, most of them advisory.
     */
    List<StandardRequirementDocument> findByStandardVersionIdAndRequirementSetVersionAndMaterial(
            String standardVersionId, String requirementSetVersion, boolean material);

    long countByStandardVersionIdAndRequirementSetVersion(
            String standardVersionId, String requirementSetVersion);
}
