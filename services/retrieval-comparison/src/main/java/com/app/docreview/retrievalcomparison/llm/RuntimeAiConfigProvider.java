package com.app.docreview.retrievalcomparison.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supplies the current model settings to the LiteLLM gateways. Architecture §10, §30.
 *
 * <p>Read from review-core, which owns the Admin-managed configuration and holds the encryption key.
 * This service deliberately has neither: §22 keeps each service's storage private, and duplicating
 * the decryption key into the worker would double the number of places a compromise yields a live
 * provider credential.
 *
 * <h2>Cached, briefly</h2>
 *
 * <p>A comparison makes one model call per requirement — up to 120 — and fetching configuration
 * before each would add 120 round trips to review-core for a value that changes when an
 * administrator edits a form. A short TTL keeps an Admin change taking effect within a minute while
 * costing one request per run rather than one per requirement.
 *
 * <p><strong>A fetch failure falls back to the last known value, then to the environment.</strong>
 * Failing the comparison instead would mean a brief review-core hiccup aborts a run that was
 * otherwise going to succeed — and the environment configuration is what the deployment started
 * with, so it is a defensible answer rather than a guess.
 */
@Component
@Slf4j
public class RuntimeAiConfigProvider {

    /**
     * How long a fetched configuration is reused.
     *
     * <p>Short enough that an administrator saving a change sees it apply to the next comparison
     * without wondering whether it took, long enough that one run does not re-fetch per requirement.
     */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final RestClient reviewCore;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public RuntimeAiConfigProvider(
            @Value("${docreview.jobs.callback-base-url:http://localhost:8080}") String reviewCoreUrl,
            @Value("${docreview.internal.service-token:}") String internalToken) {

        this.reviewCore = RestClient.builder()
                .baseUrl(reviewCoreUrl)
                // The same shared secret the callback path uses (§9.2). This endpoint carries a
                // decrypted API key, so it is internal-only and never reachable from a browser.
                .defaultHeader("X-Internal-Service-Token", internalToken)
                .requestFactory(timeoutFactory())
                .build();
    }

    public RuntimeAiConfig current() {
        var cached = cache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.config();
        }

        try {
            var fetched = reviewCore.get()
                    .uri("/internal/v1/ai-config")
                    .retrieve()
                    .body(RuntimeAiConfig.class);

            var config = fetched == null ? RuntimeAiConfig.empty() : fetched;
            cache.set(new Cached(config, Instant.now().plus(TTL)));
            return config;

        } catch (Exception e) {
            // Class name only: the response carries a provider key, and an error body can echo the
            // request that produced it (§35).
            if (cached != null) {
                // Extend the stale entry rather than retrying per requirement while review-core is
                // down — 120 failing round trips would slow the run far more than the outage does.
                log.warn("Could not read AI configuration from review-core ({}); using the last "
                        + "known values", e.getClass().getSimpleName());
                cache.set(new Cached(cached.config(), Instant.now().plus(TTL)));
                return cached.config();
            }

            // FAIL, rather than falling back to this service's own environment.
            //
            // The environment fallback used to live here, and it was actively harmful: the settings
            // are administered in review-core, so an unreachable endpoint means we do not know which
            // provider to call. Returning an empty config let the OpenAI client fall back to its
            // DEFAULT endpoint — so a misconfigured internal URL silently redirected document text
            // at api.openai.com and surfaced as "you didn't provide an API key", which points at the
            // credential instead of the actual fault.
            //
            // Failing here costs a failed comparison the administrator can retry. The alternative
            // risks sending content to an unintended provider, which cannot be undone (§35).
            throw new AiConfigUnavailableException(
                    "Could not read the model configuration from review-core ("
                            + e.getClass().getSimpleName()
                            + "). Check that the service is reachable and that a provider is "
                            + "configured under Admin -> AI & matching.", e);
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Short: this is an internal call on the critical path of every comparison, and the fallback
        // is well-defined, so waiting is worse than proceeding with environment configuration.
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    private record Cached(RuntimeAiConfig config, Instant expiresAt) {
    }
}
