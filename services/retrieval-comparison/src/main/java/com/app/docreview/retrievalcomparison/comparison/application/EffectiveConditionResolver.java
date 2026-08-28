package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.ConditionRoleSource;
import com.app.docreview.contracts.requirement.RequirementCondition;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Combines an extracted condition with any human review decision. Architecture §12.5, §19.2.
 *
 * <p><strong>An overlay, not a mutation.</strong> The extracted requirement set is immutable, so the
 * effective role is computed at read time from the extraction plus the governed override. Both remain
 * inspectable afterwards: what the model said, what a reviewer decided, and which was used.
 *
 * <p><strong>Precedence, highest first:</strong>
 * <ol>
 *   <li>policy-owner-confirmed override;</li>
 *   <li>reviewer-confirmed override;</li>
 *   <li>structured deterministic metadata — a {@code documentTypes} list decides on its own terms;</li>
 *   <li>the model's extracted role;</li>
 *   <li>UNKNOWN.</li>
 * </ol>
 *
 * <p><strong>Exclusion permission is resolved separately from the role.</strong> A CONFIRMED scope gate
 * does not exclude unless the override also authorises it. Naming what a clause does and authorising the
 * removal of a control from a review are different decisions with very different failure modes — a false
 * exclusion produces a clean-looking report that is wrong, which is far worse than one extra requirement
 * compared under uncertainty.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EffectiveConditionResolver {

    private final RequirementConditionOverrideRepository overrides;

    /**
     * The condition as it should be applied, with its provenance.
     *
     * @param overrideBatchId the batch actually used, recorded on the comparison so reopening it shows
     *                        the decision that ran rather than today's (§19.2). Null when no override
     *                        applied.
     */
    public record EffectiveCondition(
            RequirementCondition condition,
            ConditionRole effectiveRole,
            ConditionRoleSource source,
            boolean canDriveNotApplicable,
            String reviewerNotes,
            String overrideBatchId) {

        /**
         * True when this condition may lead to {@code NOT_APPLICABLE}.
         *
         * <p>BOTH gates: the role must be a scope gate AND exclusion must be authorised. Either alone
         * is insufficient, which is the whole point of separating them.
         */
        public boolean mayExclude() {
            return effectiveRole == ConditionRole.SCOPE_GATE && canDriveNotApplicable;
        }
    }

    /**
     * Resolves every condition on one requirement.
     *
     * @param conditions the extracted conditions, in stored order
     */
    public List<EffectiveCondition> resolve(String standardVersionId, String requirementSetVersion,
                                            String requirementId,
                                            List<RequirementCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }

        List<RequirementConditionOverride> loaded;
        try {
            loaded = overrides
                    .findByStandardVersionIdAndRequirementSetVersionAndRequirementId(
                            standardVersionId, requirementSetVersion, requirementId)
                    .stream()
                    .filter(o -> o.getReviewStatus() != null && o.getReviewStatus().isActive())
                    .toList();
        } catch (Exception e) {
            // Class name only (§35). A missing override store must not fail a comparison: the model's
            // own classification is a valid, if unreviewed, fallback — and it cannot exclude anything,
            // because exclusion requires an authorising override.
            log.warn("Could not read condition overrides for {} ({}); using extracted roles",
                    requirementId, e.getClass().getSimpleName());
            loaded = List.of();
        }
        // Effectively final for the lambda below.
        final var applicable = loaded;

        return conditions.stream()
                .map(condition -> resolveOne(condition, applicable))
                .toList();
    }

    private EffectiveCondition resolveOne(RequirementCondition condition,
                                          List<RequirementConditionOverride> applicable) {
        // MOST RECENTLY REVIEWED WINS, and the ordering is explicit.
        //
        // findFirst() on an unordered Mongo result was arbitrary: with two active batches matching one
        // condition, which decision applied depended on document order. A correction imported as a new
        // batch could therefore be silently ignored — and the symptom would be a stale-but-plausible
        // role, not an error.
        //
        // Retiring the superseded batch is still the intended workflow; this makes the outcome
        // well-defined when someone forgets. Nulls sort last so a row with no timestamp never
        // outranks a real review.
        var override = applicable.stream()
                .filter(o -> matches(o.getConditionText(), condition.text()))
                .max(java.util.Comparator.comparing(
                        RequirementConditionOverride::getReviewedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .orElse(null);

        if (override != null) {
            if (override.getReviewStatus() == ConditionReviewStatus.CONFIRMED) {
                return new EffectiveCondition(condition, override.effectiveRole(),
                        ConditionRoleSource.REVIEWER_OVERRIDE,
                        override.isCanDriveNotApplicable(), override.getReviewerNotes(),
                        override.getReviewBatchId());
            }
            // Reviewed but unsettled — NEEDS_POLICY_OWNER or REJECTED. UNRESOLVED is materially
            // different from MODEL_EXTRACTED: a human looked and could not decide, which is worth
            // surfacing rather than silently falling back to the model's guess.
            return new EffectiveCondition(condition, ConditionRole.UNKNOWN,
                    ConditionRoleSource.UNRESOLVED,
                    false, override.getReviewerNotes(), override.getReviewBatchId());
        }

        // No override. A documentTypes list is machine-decidable on its own terms, so it does not rest
        // on the model having named the role correctly — and it is the only unreviewed path that may
        // exclude, which is what the pre-override behaviour already relied on.
        if (!condition.documentTypes().isEmpty()) {
            return new EffectiveCondition(condition, ConditionRole.SCOPE_GATE,
                    ConditionRoleSource.STRUCTURED_METADATA, true, null, null);
        }

        // The model's role, unreviewed. canDriveNotApplicable is FALSE: an unreviewed prose scope gate
        // must not remove a control from a review. A review of 115 such predictions reclassified 113 of
        // them, so trusting them to exclude would have been wrong 98% of the time.
        return new EffectiveCondition(condition, condition.role(),
                ConditionRoleSource.MODEL_EXTRACTED, false, null, null);
    }

    /** Text match, tolerant of case and surrounding whitespace only — never a partial match. */
    private static boolean matches(String overrideText, String conditionText) {
        if (overrideText == null || conditionText == null) {
            return false;
        }
        return overrideText.trim().toLowerCase(Locale.ROOT)
                .equals(conditionText.trim().toLowerCase(Locale.ROOT));
    }
}
