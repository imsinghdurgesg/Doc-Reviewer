package com.app.docreview.reviewcore.aiconfig;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the resolved model settings to workers. Architecture §9.2, §10, §30.
 *
 * <p><strong>This is the one endpoint that returns a decrypted provider key.</strong> It exists
 * because {@code retrieval-comparison} has to authenticate to the provider on every call, and §22
 * keeps review-core's storage — including the encryption key — private to review-core. The
 * alternative, giving the worker the decryption key and Mongo access, doubles the number of places
 * a compromise yields a live credential.
 *
 * <p>Reachable only under {@code /internal/**}, which {@code InternalServiceTokenFilter} guards with
 * the shared service token and which §3 keeps off the browser-facing surface entirely. Note the
 * asymmetry with {@code AiProviderConfigController}: the ADMIN endpoint reports whether a key is set
 * and never what it is; this one returns it, and is unreachable from a browser.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalAiConfigController {

    private final AiProviderConfigService service;
    private final ReviewLimitsConfigService reviewLimitsService;
    private final org.springframework.beans.factory.ObjectProvider<CloudSyncConfigService> syncConfig;

    /** The bucket review-core is actually writing to, or null when not on GCS. */
    private String activeGcsBucket() {
        var cloud = syncConfig.getIfAvailable();
        if (cloud == null) {
            return null;
        }
        var bucket = cloud.current().getBucket();
        return bucket == null || bucket.isBlank() ? null : bucket;
    }

    /**
     * Model settings for one worker.
     *
     * <p>Nulls are meaningful: a blank field means "the administrator has not overridden this", and
     * the worker falls back to its own environment configuration. Substituting defaults here would
     * make the worker unable to tell an explicit choice from an absent one.
     */
    public record RuntimeAiConfigResponse(
            String baseUrl,
            String apiKey,
            String comparisonModel,
            String extractionModel,
            String embeddingModel,
            String comparisonInstructions,

            /** Guidance for requirement extraction and the standard profile (§10.4). */
            String extractionInstructions,
            String profileInstructions,

            /**
             * The GCS bucket review-core is currently writing to. Architecture §22, §41.
             *
             * <p>Served here because the worker READS BACK every object review-core writes, and an
             * administrator can repoint the bucket from the Admin screen. Without it the worker keeps
             * using its environment value, so a bucket change makes review-core write to one bucket
             * while the worker looks in another — the artifact exists, nothing can find it, and the
             * sync still reports success.
             *
             * <p>Not read from review-core's database by the worker: §22 keeps each service's storage
             * private. This endpoint is the sanctioned channel, and the worker already polls it.
             *
             * <p>Null when the deployment is not on GCS, in which case the worker's own configuration
             * is authoritative.
             */
            String gcsBucket) {
    }

    /**
     * Review limits, for the worker. Architecture §10.5.
     *
     * <p>Fetched per job rather than read from the worker's own configuration, because the requirement
     * cap is enforced in BOTH services and the two must agree. As separate environment variables they
     * could drift, and the failure was silent in the worst direction: a comparison passed review-core's
     * up-front check, then had requirements dropped by the worker's bound and reported as never
     * attempted.
     */
    public record RuntimeReviewLimitsResponse(
            int recommendedStandards,
            int maxRequirementsPerComparison) {
    }

    @GetMapping("/review-limits")
    public RuntimeReviewLimitsResponse reviewLimits() {
        var limits = reviewLimitsService.current();
        return new RuntimeReviewLimitsResponse(
                limits.getRecommendedStandards(),
                limits.getMaxRequirementsPerComparison());
    }

    @GetMapping("/ai-config")
    public RuntimeAiConfigResponse aiConfig() {
        var config = service.current();
        return new RuntimeAiConfigResponse(
                service.effectiveBaseUrl(config),
                service.effectiveApiKey(config),
                config.getComparisonModel(),
                config.getExtractionModel(),
                config.getEmbeddingModel(),
                config.getComparisonInstructions(),
                config.getExtractionInstructions(),
                config.getProfileInstructions(),
                activeGcsBucket());
    }
}
