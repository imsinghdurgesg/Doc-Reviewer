package com.app.docreview.reviewcore.aiconfig;

import com.app.docreview.reviewcore.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review limits, for the Admin screen. Architecture §10.5, §15.5.
 *
 * <p>READ is open to any signed-in user; WRITE is ADMIN only. A reviewer benefits from seeing the
 * requirement cap — it is the number that rejects their comparison — and hiding it would leave them
 * reading an error that names a limit they cannot look up.
 */
@RestController
@RequestMapping("/api/admin/review-limits")
@RequiredArgsConstructor
public class ReviewLimitsController {

    private final ReviewLimitsConfigService service;
    private final CurrentUser currentUser;

    /**
     * The effective limits, plus the ceilings the UI should enforce on its inputs.
     *
     * <p>The maxima are returned rather than hard-coded in the client: a form that allows a value the
     * server will clamp tells the user their choice was accepted when it was not.
     */
    public record ReviewLimitsResponse(
            int recommendedStandards,
            int maxRequirementsPerComparison,
            int maxAllowedRecommendedStandards,
            int maxAllowedRequirementsPerComparison) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','STANDARDS_ADMIN','ADMIN')")
    public ReviewLimitsResponse get() {
        return toResponse(service.current());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ReviewLimitsResponse update(
            @RequestBody ReviewLimitsConfigService.ReviewLimitsUpdate update) {
        return toResponse(service.update(update, currentUser.id()));
    }

    private ReviewLimitsResponse toResponse(ReviewLimitsConfig c) {
        return new ReviewLimitsResponse(
                c.getRecommendedStandards(),
                c.getMaxRequirementsPerComparison(),
                // The live library count, not a constant: the form's maximum then tracks how many
                // standards actually exist, and an administrator is never offered a number that
                // could have no effect.
                service.maxRecommendableStandards(),
                ReviewLimitsConfig.MAX_ALLOWED_REQUIREMENTS);
    }
}
