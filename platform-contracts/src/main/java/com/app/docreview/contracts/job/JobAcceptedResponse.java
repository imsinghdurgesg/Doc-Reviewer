package com.app.docreview.contracts.job;

/**
 * A worker's answer to a job dispatch. Architecture §21 steps 3–4.
 *
 * <p>Returned with 202 Accepted as soon as the job is claimed, BEFORE the work runs. A worker that
 * held the connection for the duration would turn every long extraction into a dispatch timeout, and
 * review-core would re-dispatch work that was in fact progressing.
 *
 * <p>{@code duplicate} distinguishes "claimed now" from "already claimed under this idempotency key".
 * Both are success — a re-dispatch must be a no-op, not an error (§21) — but only the first should
 * move the caller's job into DISPATCHED and start the clock on it.
 */
public record JobAcceptedResponse(

        String jobId,

        /** The worker's own record ID, for cross-service diagnostics. */
        String workerJobId,

        /** True when this key was already claimed and no new work was started. */
        boolean duplicate) {

    public static JobAcceptedResponse accepted(String jobId, String workerJobId) {
        return new JobAcceptedResponse(jobId, workerJobId, false);
    }

    public static JobAcceptedResponse duplicate(String jobId, String workerJobId) {
        return new JobAcceptedResponse(jobId, workerJobId, true);
    }
}
