package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.job.JobAcceptedResponse;
import com.app.docreview.contracts.job.RetrievalComparisonCommand;
import com.app.docreview.contracts.job.StandardIndexingCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;

/**
 * Internal job endpoints for indexing and comparison. Architecture §20.3, §21 steps 3–5.
 *
 * <p>Both endpoints answer 202 as soon as the job is CLAIMED, before the work runs (§21 step 4).
 * That matters more here than anywhere else: a standard indexing run makes one LLM call per candidate
 * group and a comparison makes one per requirement, so either can take minutes. Holding the
 * connection would guarantee a dispatch timeout and a pointless re-dispatch.
 *
 * <p>Claim first, then submit: if the executor is saturated the claim already exists, so the recovery
 * sweep can pick the job up later (§21 step 8). Submitting first would run work nothing owns.
 *
 * <p>Reachable only with the internal service token (see {@link InternalTokenFilter}) — this service
 * holds the LiteLLM key (§8.3), so an unauthenticated dispatch is a route to model spend.
 */
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
@Slf4j
public class RetrievalComparisonJobController {

    private final JobClaimService claimService;
    private final StandardIndexingJobRunner indexingRunner;
    private final ComparisonJobRunner comparisonRunner;
    private final ExecutorService jobExecutor;

    /** Standard requirement extraction and indexing (§20.1). */
    @PostMapping("/standard-indexing/jobs")
    public ResponseEntity<JobAcceptedResponse> submitIndexing(
            @Valid @RequestBody StandardIndexingCommand command) {

        var claim = claimService.claim(command);
        if (claim.created()) {
            submit(claim, () -> indexingRunner.run(claim.job()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(claim.toResponse());
    }

    /** Evidence retrieval and requirement comparison (§20.2). */
    @PostMapping("/comparison/jobs")
    public ResponseEntity<JobAcceptedResponse> submitComparison(
            @Valid @RequestBody RetrievalComparisonCommand command) {

        var claim = claimService.claim(command);
        if (claim.created()) {
            submit(claim, () -> comparisonRunner.run(claim.job()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(claim.toResponse());
    }

    /**
     * Submits the work, tolerating a saturated executor.
     *
     * <p>Still answers 202 on rejection: the work IS accepted and claimed, just not started, and the
     * sweep re-dispatches it once capacity frees up. A 503 would make review-core treat a valid job
     * as failed.
     */
    private void submit(JobClaimService.Claim claim, Runnable work) {
        try {
            jobExecutor.submit(work);
        } catch (JobExecutorConfig.JobRejectedException e) {
            log.warn("Executor saturated; job {} deferred to the recovery sweep",
                    claim.job().getId());
        }
    }
}
