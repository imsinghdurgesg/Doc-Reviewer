package com.app.docreview.reviewcore.aiconfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Reads and updates review limits. Architecture §10.5, §15.5.
 *
 * <p>Returns the DEFAULTS when nothing is stored, rather than null or zero. A deployment that has
 * never visited the Admin screen must still enforce a cap: zero would read as "no limit" and remove
 * the bound entirely, which is the opposite of what the setting is for.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewLimitsConfigService {

    private final ReviewLimitsConfigRepository repository;
    /** Counted at read time so the recommendation ceiling tracks the library as it grows. */
    private final com.app.docreview.reviewcore.standards.repository.StandardVersionRepository
            standardVersions;

    public ReviewLimitsConfig current() {
        return repository.findById(ReviewLimitsConfig.SINGLETON_ID)
                .orElseGet(ReviewLimitsConfig::new);
    }

    /**
     * Applies an update, clamping each value into its allowed range.
     *
     * <p><strong>Clamped rather than rejected.</strong> An out-of-range number is a typo far more often
     * than an intention — 5000 for 500 — and refusing the whole save would lose the other field too.
     * The clamp is logged so an administrator who typed 5000 and got 2000 can see why, rather than
     * assuming the save silently failed.
     */
    public ReviewLimitsConfig update(ReviewLimitsUpdate update, String actor) {
        var config = current();

        if (update.recommendedStandards() != null) {
            // Bounded by the LIBRARY, not by a constant. Recommending more standards than exist is a
            // number with no effect, and a fixed ceiling was arbitrary either way — 5 forbade
            // pre-selecting standards the selection screen was already ranking.
            int v = clamp(update.recommendedStandards(), 1,
                    maxRecommendableStandards(), "recommendedStandards");
            config.setRecommendedStandards(v);
        }
        if (update.maxRequirementsPerComparison() != null) {
            // Floor of 1, not 0. Zero would reject every comparison, and an administrator would see
            // "0 of 0 requirements" with no indication that a setting caused it.
            int v = clamp(update.maxRequirementsPerComparison(), 1,
                    ReviewLimitsConfig.MAX_ALLOWED_REQUIREMENTS, "maxRequirementsPerComparison");
            config.setMaxRequirementsPerComparison(v);
        }

        var saved = repository.save(config);
        // Values only, never the actor's identity beyond what §35 permits: these are numbers, and
        // knowing who changed a limit is exactly what an audit of a surprising cap needs.
        log.info("Review limits updated by {}: recommendedStandards={} maxRequirements={}",
                actor, saved.getRecommendedStandards(), saved.getMaxRequirementsPerComparison());
        return saved;
    }

    /**
     * How many standards could be recommended: the count eligible for new comparisons.
     *
     * <p>Floor of 1 so an empty library does not produce a zero ceiling that rejects every save. A
     * deployment with no indexed standards cannot run a comparison anyway, and refusing to store a
     * setting would be a confusing second symptom of the same cause.
     */
    public int maxRecommendableStandards() {
        int comparable = standardVersions.findByLifecycleAndIndexingState(
                com.app.docreview.reviewcore.standards.domain.StandardLifecycle.ACTIVE,
                com.app.docreview.contracts.job.StandardIndexingState.INDEXED).size();
        return Math.max(1, comparable);
    }

    private static int clamp(int value, int min, int max, String field) {
        if (value < min || value > max) {
            log.warn("Review limit {} was {}, clamped into {}..{}", field, value, min, max);
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Partial update: a null field is left unchanged. */
    public record ReviewLimitsUpdate(
            Integer recommendedStandards,
            Integer maxRequirementsPerComparison) {
    }
}
