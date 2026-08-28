package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.job.JobState;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJob;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Re-dispatches stale jobs and retires unrecoverable ones. Architecture §21 step 8.
 *
 * <p>This is what makes the persist-before-dispatch pattern (§21) actually recover rather than merely
 * record. Three real failures leave a job non-terminal with nobody coming back for it:
 *
 * <ul>
 *   <li>review-core died between writing the job and dispatching it — the job is PENDING and was
 *       never sent.</li>
 *   <li>The worker was down, so dispatch failed and the job was returned to PENDING.</li>
 *   <li>The worker claimed the job and then died mid-run — DISPATCHED or RUNNING, with a claim that
 *       will never complete.</li>
 * </ul>
 *
 * <p>Without this sweep each of those is a job that sits forever while the UI shows work in progress.
 *
 * <p><strong>Re-dispatch is safe because the worker's claim is idempotent</strong> (§21, §39 rule 23).
 * A job that in fact completed is recognised by its idempotency key, and the worker resends its
 * callback instead of redoing the work. That is why the sweep can be blunt about staleness rather than
 * needing to distinguish "slow" from "dead" — a wrong guess costs one extra HTTP round trip, not
 * duplicate artifacts or duplicate LLM spend.
 *
 * <p>Staleness is measured on {@code updatedAt}, which workers advance as they progress (§21 step 6).
 * A long-running extraction that reports progress therefore never looks stale, while a silent one
 * does — which is the distinction that matters.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobRecoverySweep {

    /** Non-terminal states a job can be stuck in. */
    private static final List<JobState> RECOVERABLE = List.of(
            JobState.PENDING, JobState.DISPATCHED, JobState.RUNNING, JobState.RETRY_WAIT);

    private final ProcessingJobRepository jobs;
    private final HttpJobDispatcher dispatcher;
    private final JobDispatchProperties properties;

    /**
     * Runs on a fixed delay rather than a fixed rate.
     *
     * <p>Fixed delay measures from the END of the previous run, so a sweep that takes longer than the
     * interval cannot overlap with itself. Overlapping sweeps would both re-dispatch the same stale
     * jobs — harmless thanks to idempotency, but pointless load on the workers.
     */
    @Scheduled(fixedDelayString = "${docreview.jobs.sweep-interval:PT2M}")
    public void sweep() {
        Instant threshold = Instant.now().minus(properties.staleAfter());
        var stale = jobs.findByStateInAndUpdatedAtBefore(RECOVERABLE, threshold);

        if (stale.isEmpty()) {
            return;
        }

        log.info("Recovery sweep found {} stale job(s) older than {}", stale.size(),
                properties.staleAfter());

        for (ProcessingJob job : stale) {
            if (job.retryable()) {
                log.info("Re-dispatching stale job {} ({}) in state {}, attempt {} of {}",
                        job.getId(), job.getJobType(), job.getState(),
                        job.getAttempt(), job.getMaxAttempts());
                // send() inside redispatch handles its own failures and records the outcome, so one
                // unreachable worker does not abort the rest of the sweep.
                dispatcher.redispatch(job);
            } else {
                retire(job);
            }
        }
    }

    /**
     * Marks a job failed after its attempts are exhausted.
     *
     * <p>§18.4 requires retries to stop rather than loop. A job retried forever is worse than a failed
     * one: it consumes worker capacity indefinitely and never tells the user that their upload will
     * not produce a result.
     */
    private void retire(ProcessingJob job) {
        job.setState(JobState.FAILED);
        job.setFailureReason("Job did not complete after " + job.getAttempt()
                + " attempts and was retired by the recovery sweep.");
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobs.save(job);

        log.warn("Retired job {} ({}) after {} attempts", job.getId(), job.getJobType(),
                job.getAttempt());
    }
}
