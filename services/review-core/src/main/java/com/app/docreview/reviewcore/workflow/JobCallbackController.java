package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.job.JobCallback;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker completion callbacks. Architecture §20.3, §21 steps 6–7.
 *
 * <p>Three routes rather than one, matching §20.3, even though they share a handler. The path is what
 * appears in access logs and metrics, so keeping them distinct makes "indexing callbacks are failing"
 * visible without parsing bodies. The job type in the payload drives the actual behaviour.
 *
 * <p><strong>A duplicate callback returns 200, not an error.</strong> §21 requires the repeat to be a
 * no-op, and a worker that received an error would keep retrying a callback that had already been
 * applied. The response body distinguishes applied from ignored for diagnostics.
 *
 * <p>An unknown job or a key mismatch returns 4xx: those indicate a real disagreement between worker
 * and orchestrator, and a worker should not retry them indefinitely.
 */
@RestController
@RequestMapping("/internal/v1/callbacks")
@RequiredArgsConstructor
@Slf4j
public class JobCallbackController {

    private final JobCallbackService callbackService;
    private final JobProgressService progressService;

    @PostMapping("/document-processing")
    public ResponseEntity<Response> documentProcessing(@Valid @RequestBody JobCallback callback) {
        return handle(callback);
    }

    @PostMapping("/standard-indexing")
    public ResponseEntity<Response> standardIndexing(@Valid @RequestBody JobCallback callback) {
        return handle(callback);
    }

    @PostMapping("/comparison")
    public ResponseEntity<Response> comparison(@Valid @RequestBody JobCallback callback) {
        return handle(callback);
    }

    /**
     * Mid-run progress for a comparison. Architecture §21.
     *
     * <p>Separate from the completion callbacks above and deliberately weaker: it cannot finish,
     * fail, or reopen a comparison — see {@link JobProgressService}. The comparison is named in the
     * path because a progress report carries no result to look it up by.
     *
     * <p>Always 200, even for an unknown or finished comparison. A worker must never slow down or
     * retry because a progress hint was not wanted; the body says what happened for anyone debugging.
     */
    @PostMapping("/comparison/{comparisonId}/progress")
    public ResponseEntity<Response> comparisonProgress(
            @PathVariable String comparisonId,
            @Valid @RequestBody com.app.docreview.contracts.job.JobProgress progress) {

        var outcome = progressService.apply(comparisonId, progress);
        return ResponseEntity.ok(new Response(progress.jobId(),
                outcome == JobProgressService.Outcome.APPLIED, outcome.name()));
    }

    /** Outcome of applying a callback. {@code applied} is false for an ignored duplicate. */
    public record Response(String jobId, boolean applied, String outcome) {}

    private ResponseEntity<Response> handle(JobCallback callback) {
        var outcome = callbackService.apply(callback);
        var body = new Response(callback.jobId(),
                outcome == JobCallbackService.Outcome.APPLIED, outcome.name());

        return switch (outcome) {
            // Both are success from the worker's perspective: the result is recorded, so stop
            // resending.
            case APPLIED, IGNORED_DUPLICATE -> ResponseEntity.ok(body);
            case UNKNOWN_JOB -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            case KEY_MISMATCH -> ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        };
    }
}
