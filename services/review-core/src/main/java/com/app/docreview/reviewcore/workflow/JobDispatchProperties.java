package com.app.docreview.reviewcore.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Worker endpoints, dispatch timeouts, and retry bounds. Architecture §9.2, §21, §10.5.
 *
 * <p>Timeouts are separate values because they fail differently. A connect timeout means the worker
 * is down and re-dispatch is correct; a read timeout means the request may have been received, so a
 * blind retry risks duplicate work — which is why the worker claim is idempotent (§21).
 *
 * <p>Dispatch timeouts are SHORT. The worker answers 202 as soon as it claims the job (§21 step 4),
 * so a slow dispatch response means the worker is unhealthy, not that the work is long-running.
 * Sizing this for the duration of the work would hide a dead worker for minutes.
 */
@ConfigurationProperties(prefix = "docreview.jobs")
public record JobDispatchProperties(

        Worker documentProcessing,

        Worker retrievalComparison,

        /**
         * Shared secret presented to workers. §3 keeps workers off the public network, but network
         * placement is not authentication: anything reaching the internal network could otherwise
         * dispatch jobs. Blank fails startup (see {@link #validate()}).
         */
        String internalServiceToken,

        /** Base URL review-core advertises to workers for callbacks (§20.3). */
        String callbackBaseUrl,

        Duration connectTimeout,

        Duration readTimeout,

        /** Attempt bound stored on the job record. §18.4 requires retries to stop, not loop. */
        Integer maxAttempts,

        /** Age after which a non-terminal job is considered stale and re-dispatched (§21 step 8). */
        Duration staleAfter) {

    public JobDispatchProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        staleAfter = staleAfter == null ? Duration.ofMinutes(15) : staleAfter;
        callbackBaseUrl = callbackBaseUrl == null ? "http://review-core:8080" : callbackBaseUrl;
    }

    public record Worker(String baseUrl) {
    }

    /**
     * Fails fast on missing configuration.
     *
     * <p>Called from the dispatcher's constructor rather than relying on {@code @Validated}, so the
     * context refuses to start rather than failing on the first upload — a deployment that accepts
     * documents and cannot process them is worse than one that does not start.
     */
    public void validate() {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException(
                    "docreview.jobs.internal-service-token is not set. Workers reject unauthenticated "
                            + "dispatch, so every job would fail. Set INTERNAL_SERVICE_TOKEN.");
        }
        requireBaseUrl(documentProcessing, "document-processing");
        requireBaseUrl(retrievalComparison, "retrieval-comparison");
    }

    private void requireBaseUrl(Worker worker, String name) {
        if (worker == null || worker.baseUrl() == null || worker.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "docreview.jobs." + name + ".base-url is not set; jobs cannot be dispatched.");
        }
    }
}
