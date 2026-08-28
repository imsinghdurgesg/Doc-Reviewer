package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.job.DocumentProcessingCommand;
import com.app.docreview.contracts.job.JobAcceptedResponse;
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
 * Internal job endpoint. Architecture §20.3, §21 steps 3–5.
 *
 * <p>Answers 202 as soon as the job is CLAIMED, before the work runs (§21 step 4). Holding the
 * connection for the duration would turn every large document into a dispatch timeout, and
 * review-core would re-dispatch work that was progressing normally.
 *
 * <p>The sequence is claim, then submit, then answer — and claim comes first for a specific reason:
 * if submission is rejected because the executor is saturated, the claim already exists, so the
 * recovery sweep can pick the job up later. Submitting first and claiming after would run the work
 * without a record of who owns it.
 *
 * <p>Reachable only with the internal service token (see {@link InternalTokenFilter}).
 */
@RestController
@RequestMapping("/internal/v1/document-processing")
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingJobController {

    private final JobClaimService claimService;
    private final DocumentProcessingJobRunner runner;
    private final ExecutorService jobExecutor;

    @PostMapping("/jobs")
    public ResponseEntity<JobAcceptedResponse> submit(
            @Valid @RequestBody DocumentProcessingCommand command) {

        var claim = claimService.claim(command);

        // A duplicate claim means the work is already running or finished. Answering 202 rather than
        // a conflict is what makes a repeated dispatch a no-op (§21).
        if (!claim.created()) {
            return ResponseEntity.accepted().body(claim.toResponse());
        }

        try {
            jobExecutor.submit(() -> runner.run(claim.job()));
        } catch (JobExecutorConfig.JobRejectedException e) {
            // The claim stands and the job is not terminal, so review-core's sweep re-dispatches it
            // once capacity frees up (§21 step 8). Still a 202: the work IS accepted, just not
            // started, and a 503 would make review-core mark a perfectly valid job as failed.
            log.warn("Executor saturated; job {} deferred to the recovery sweep", claim.job().getId());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(claim.toResponse());
    }
}
