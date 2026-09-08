package com.app.docreview.retrievalcomparison.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads Admin-configured review limits from review-core. Architecture §10.5, §15.5.
 *
 * <p><strong>Why the worker fetches rather than holding its own copy.</strong> The requirement cap is
 * enforced twice — review-core refuses an oversized selection up front, this service bounds what it
 * loads as a backstop. When those were separate environment variables they could drift, and the
 * failure was silent in the worst direction: a comparison passed the front door, then had requirements
 * dropped here and reported as never attempted. One stored row read by both removes that.
 *
 * <p>Mirrors {@code RuntimeAiConfigProvider}'s caching so a run does not re-fetch per requirement.
 *
 * <p><strong>Unlike that provider, this one falls back to defaults instead of failing.</strong> The
 * difference is what each value does. An unknown AI provider means we do not know which endpoint to
 * call, and guessing sent document text to a default host — so failing is correct there. An unknown
 * limit only means we do not know the administrator's preferred ceiling; refusing to run every
 * comparison because a settings endpoint is briefly unreachable would be a far worse outcome than
 * using the shipped default for a minute.
 */
@Component
@Slf4j
public class ReviewLimitsProvider {

    /** Matches RuntimeAiConfigProvider: long enough to avoid per-requirement fetches, short enough
     *  that an administrator's change applies to the next comparison. */
    private static final Duration TTL = Duration.ofSeconds(60);

    /** Shipped defaults, used until review-core answers. Deliberately the same numbers as
     *  {@code ReviewLimitsConfig}, so an outage does not change behaviour. */
    private static final int DEFAULT_RECOMMENDED = 1;
    private static final int DEFAULT_MAX_REQUIREMENTS = 500;

    private final RestClient reviewCore;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public ReviewLimitsProvider(
            @Value("${docreview.jobs.callback-base-url:http://localhost:8080}") String reviewCoreUrl,
            @Value("${docreview.internal.service-token:}") String internalToken) {

        this.reviewCore = RestClient.builder()
                .baseUrl(reviewCoreUrl)
                .defaultHeader("X-Internal-Service-Token", internalToken)
                .requestFactory(timeoutFactory())
                .build();
    }

    /** The effective limits, cached for {@link #TTL}. */
    public ReviewLimits current() {
        var cached = cache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.limits();
        }
        try {
            var limits = reviewCore.get()
                    .uri("/internal/v1/review-limits")
                    .retrieve()
                    .body(ReviewLimits.class);

            if (limits == null || limits.maxRequirementsPerComparison() <= 0) {
                // A zero or absent cap would remove the bound entirely, which is the opposite of what
                // the setting exists for. Treated as an unusable answer rather than an instruction.
                log.warn("review-core returned no usable review limits; using defaults");
                return fallback(cached);
            }
            cache.set(new Cached(limits, Instant.now().plus(TTL)));
            return limits;

        } catch (Exception e) {
            // Class name only (§35): an error body can echo the request that produced it.
            log.warn("Could not read review limits from review-core ({}); using {}",
                    e.getClass().getSimpleName(),
                    cached != null ? "the last known values" : "shipped defaults");
            return fallback(cached);
        }
    }

    /** Extends a stale entry rather than re-requesting per requirement while review-core is down. */
    private ReviewLimits fallback(Cached cached) {
        var limits = cached != null ? cached.limits()
                : new ReviewLimits(DEFAULT_RECOMMENDED, DEFAULT_MAX_REQUIREMENTS);
        cache.set(new Cached(limits, Instant.now().plus(TTL)));
        return limits;
    }

    /** Mirrors InternalAiConfigController.RuntimeReviewLimitsResponse. */
    public record ReviewLimits(int recommendedStandards, int maxRequirementsPerComparison) {
    }

    private record Cached(ReviewLimits limits, Instant expiresAt) {
    }

    /**
     * Short timeouts.
     *
     * <p>This is a settings lookup on the critical path of a comparison. A hung request would stall a
     * run for far longer than falling back to the previous values costs.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        return factory;
    }
}
