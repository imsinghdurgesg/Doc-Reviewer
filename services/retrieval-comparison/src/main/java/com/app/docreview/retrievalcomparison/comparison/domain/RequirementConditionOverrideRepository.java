package com.app.docreview.retrievalcomparison.comparison.domain;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Reads human review decisions for a requirement's conditions. Architecture §12.5.
 *
 * <p>Queried per requirement rather than per condition: a requirement has at most a handful of
 * conditions, and one lookup that returns them all avoids a query per condition across a whole
 * comparison.
 */
public interface RequirementConditionOverrideRepository
        extends MongoRepository<RequirementConditionOverride, String> {

    /**
     * Every override recorded for one requirement, including RETIRED ones.
     *
     * <p>Retired rows are returned rather than filtered in the query so the caller applies one
     * consistent activeness rule — a filter here plus a filter there is how a superseded decision
     * eventually leaks back into a live comparison.
     */
    List<RequirementConditionOverride> findByStandardVersionIdAndRequirementSetVersionAndRequirementId(
            String standardVersionId, String requirementSetVersion, String requirementId);

    /** A whole review batch, for auditing or retiring it. */
    List<RequirementConditionOverride> findByReviewBatchId(String reviewBatchId);

    /**
     * Every condition in a given review state, across all standards.
     *
     * <p>Used to build the policy-owner queue from {@code NEEDS_POLICY_OWNER}. Deliberately NOT scoped
     * to one standard version: an unresolved condition is an open organizational question, and the same
     * phrase recurs across versions of the same standard. A per-version view would show it as several
     * separate small problems rather than one decision worth a policy owner's time.
     */
    List<RequirementConditionOverride> findByReviewStatus(ConditionReviewStatus reviewStatus);
}
