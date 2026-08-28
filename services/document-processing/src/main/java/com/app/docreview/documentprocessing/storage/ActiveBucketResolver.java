package com.app.docreview.documentprocessing.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves which GCS bucket to act on. Architecture §22, §41.
 *
 * <p><strong>Why this exists.</strong> An administrator can repoint the bucket from the Admin screen,
 * and review-core honours that immediately. This worker READS BACK every object review-core writes,
 * so if it kept using its own environment value the two would diverge: review-core writes the
 * canonical artifact to the new bucket, this service looks for the source in the old one, and the
 * failure reads as a corrupt document rather than a configuration change.
 *
 * <p>Read from review-core's internal config endpoint rather than its database — §22 keeps each
 * service's storage private, and this endpoint already exists for the same reason (the model
 * provider settings live there too).
 *
 * <p><strong>Cached, and it fails safe.</strong> A parse fetches this once per job, not per object.
 * When review-core is unreachable the environment value stands: that is the bucket the deployment
 * booted with, so it is a defensible answer rather than a guess, and it keeps parsing working through
 * a brief review-core restart.
 */
@Component
@Slf4j
public class ActiveBucketResolver {

    /** Short: an Admin change should apply to the next job, not the next restart. */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final String reviewCoreUrl;
    private final String internalToken;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public ActiveBucketResolver(
            @Value("${docreview.services.review-core.base-url:}") String reviewCoreUrl,
            @Value("${docreview.internal.service-token:}") String internalToken) {
        this.reviewCoreUrl = reviewCoreUrl;
        this.internalToken = internalToken;
    }

    /**
     * @param fallback the worker's own configured bucket, used when review-core says nothing
     * @return the bucket to read and write, never null unless {@code fallback} is
     */
    public String resolve(String fallback) {
        if (reviewCoreUrl == null || reviewCoreUrl.isBlank()) {
            // Not configured to ask. Correct for a deployment that never repoints the bucket.
            return fallback;
        }

        var cached = cache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.bucket() == null ? fallback : cached.bucket();
        }

        try {
            var body = RestClient.builder()
                    .baseUrl(reviewCoreUrl)
                    .requestFactory(timeoutFactory())
                    .defaultHeader("X-Internal-Service-Token", internalToken)
                    .build()
                    .get()
                    .uri("/internal/v1/ai-config")
                    .retrieve()
                    .body(Map.class);

            String bucket = body == null ? null : (String) body.get("gcsBucket");
            cache.set(new Cached(bucket, Instant.now().plus(TTL)));
            return bucket == null || bucket.isBlank() ? fallback : bucket;

        } catch (Exception e) {
            // Class name only: the response carries a provider API key (§35).
            log.debug("Could not read the active bucket from review-core ({}); using {}",
                    e.getClass().getSimpleName(), fallback);
            // Cached as a negative so a review-core outage does not mean one failed request per job.
            cache.set(new Cached(null, Instant.now().plus(TTL)));
            return fallback;
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Short: this sits on the critical path of every parse and the fallback is well-defined, so
        // waiting is worse than proceeding with the configured bucket.
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    private record Cached(String bucket, Instant expiresAt) {
    }
}
