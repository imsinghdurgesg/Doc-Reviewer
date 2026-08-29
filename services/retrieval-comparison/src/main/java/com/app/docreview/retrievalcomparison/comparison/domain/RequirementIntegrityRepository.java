package com.app.docreview.retrievalcomparison.comparison.domain;

import com.app.docreview.contracts.enums.RequirementIntegrityStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Reads governed requirement-integrity decisions. Architecture §12.7, §18.3.
 *
 * <p>Loaded ONCE PER COMPARISON for the whole selected requirement set, not per requirement. A
 * per-requirement query would add a round trip to each of 191 requirements to discover that ~11 of them
 * have a record, which is the wrong trade for a lookup that is empty in the overwhelming majority.
 */
public interface RequirementIntegrityRepository
        extends MongoRepository<RequirementIntegrity, String> {

    /** Every record for one extraction generation of one standard. */
    List<RequirementIntegrity> findByStandardVersionIdAndRequirementSetVersion(
            String standardVersionId, String requirementSetVersion);

    /** A whole import batch, for auditing or withdrawing it. */
    List<RequirementIntegrity> findByReviewBatchId(String reviewBatchId);

    /** Everything in one state, for the reviewer queue. */
    List<RequirementIntegrity> findByIntegrityStatus(RequirementIntegrityStatus integrityStatus);
}
