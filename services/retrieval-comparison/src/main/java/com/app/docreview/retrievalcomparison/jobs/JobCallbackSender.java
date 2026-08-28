package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobProgress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Delivers completion callbacks to review-core. Architecture §21 step 6, §20.3.
 *
 * <p>The callback URL comes from the job command rather than configuration, so the worker needs no
 * knowledge of review-core's routes and the reachable address can differ between Compose and local
 * development (§20.3).
 *
 * <p><strong>A failed callback must not fail the job.</strong> Here that principle is expensive to
 * violate: the work has already spent LLM budget and persisted validated results, so throwing away a
 * completed comparison over an undelivered notification would mean paying for it twice. review-core's
 * sweep re-dispatches, the idempotent claim recognises the job, and the callback is resent from the
 * existing record (§21 step 8).
 */
@Component
@Slf4j
public class JobCallbackSender {

    private final RestClient client;

    public JobCallbackSender(
            @Value("${docreview.internal.service-token:}") String serviceToken,
            @Value("${docreview.jobs.callback-connect-timeout:3s}") Duration connectTimeout,
            @Value("${docreview.jobs.callback-read-timeout:10s}") Duration readTimeout) {

        var httpClient = HttpClient.newBuilder()
                // HTTP/1.1 pinned, matching HttpEvidenceSpanReader and the dispatcher. The JDK
                // client defaults to HTTP_2, whose cleartext h2c upgrade preamble can cause a server
                // that does not negotiate it to drop the request BODY — here that would silently
                // deliver an empty callback and lose an already-paid-for comparison result.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(InternalTokenFilter.TOKEN_HEADER, serviceToken)
                .build();
    }

    /** Posts the callback. Returns true when review-core acknowledged it. */
    public boolean send(String callbackUrl, JobCallback callback) {
        try {
            client.post()
                    .uri(callbackUrl)
                    .body(callback)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Delivered callback for job {} in state {}",
                    callback.jobId(), callback.terminalState());
            return true;
        } catch (Exception e) {
            // Exception TYPE only: the message can echo the request body, which carries failure
            // reasons and unit errors (§10.5).
            log.warn("Callback delivery failed for job {} ({}); review-core's recovery sweep will "
                            + "re-dispatch and the claim will resend",
                    callback.jobId(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Reports mid-run progress. Architecture §21.
     *
     * <p><strong>Fire-and-forget, unlike {@link #send}.</strong> A failed progress report is not
     * retried and not reported as failed, because the information is worthless by the time a retry
     * would land and the run must not slow down for a status hint. A completion callback is the
     * opposite: it carries the result, so its delivery is tracked and re-attempted.
     *
     * <p>Logged at DEBUG. One comparison emits one of these per requirement, so INFO would bury the
     * lines that describe the outcome.
     */
    public void sendProgress(String callbackUrl, JobProgress progress) {
        try {
            client.post()
                    .uri(callbackUrl)
                    .body(progress)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Progress for job {}: {} {}/{}", progress.jobId(), progress.progressState(),
                    progress.completedUnits(), progress.totalUnits());
        } catch (Exception e) {
            // Type only (§10.5), and DEBUG: an unreachable review-core is already reported by the
            // completion path, and repeating it per requirement would be noise.
            log.debug("Progress delivery failed for job {} ({}); continuing",
                    progress.jobId(), e.getClass().getSimpleName());
        }
    }
}
