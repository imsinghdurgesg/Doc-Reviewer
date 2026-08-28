package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.job.JobCallback;
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
 * <p>The callback URL comes from the job command, not from configuration: the worker should not need
 * to know review-core's routes, and under Compose versus local development the reachable address
 * differs (§20.3).
 *
 * <p><strong>A failed callback must not fail the job.</strong> The work is done and its artifacts are
 * persisted; losing the notification is a delivery problem. review-core's recovery sweep re-dispatches
 * the job, the worker's idempotent claim recognises it, and the callback is re-sent from the existing
 * record (§21 step 8). Throwing here would instead lose the completed work.
 *
 * <p>Presents the internal token on the callback too. review-core must not accept a completion report
 * from an unauthenticated caller — that would let anything on the network mark a comparison finished.
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
                // deliver an empty callback and lose the job's result.
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

    /**
     * Posts the callback. Returns true when review-core acknowledged it.
     *
     * @param callbackUrl absolute URL supplied by the dispatcher in the job command
     */
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
            // Exception TYPE only. The message can contain the request body, which for a callback
            // includes failure reasons and unit errors (§10.5).
            log.warn("Callback delivery failed for job {} ({}); review-core's recovery sweep will "
                            + "re-dispatch and the claim will resend",
                    callback.jobId(), e.getClass().getSimpleName());
            return false;
        }
    }
}
