package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.job.DocumentProcessingCommand;
import com.app.docreview.contracts.job.JobAcceptedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Claims dispatched jobs exactly once. Architecture §21 step 3, §39 rule 23.
 *
 * <p>The claim is an INSERT that relies on the unique index on {@code idempotencyKey}, not a
 * read-then-write. Two concurrent dispatches of the same job both attempt the insert; the database
 * serialises them and exactly one wins. A check-then-insert has a window between the two operations
 * where both callers see nothing and both proceed — which is how the same document gets parsed twice
 * and two sets of spans are written.
 *
 * <p>{@link DuplicateKeyException} is therefore the SUCCESS path for a re-dispatch, not an error. It
 * means the work is already claimed, which is exactly what §21 requires a repeated request to be.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobClaimService {

    private final WorkerJobRepository jobs;

    /** The outcome of a claim attempt: the record, and whether this caller created it. */
    public record Claim(WorkerJob job, boolean created) {

        public JobAcceptedResponse toResponse() {
            return created
                    ? JobAcceptedResponse.accepted(job.getOriginJobId(), job.getId())
                    : JobAcceptedResponse.duplicate(job.getOriginJobId(), job.getId());
        }
    }

    /**
     * Claims the job described by the command, or returns the existing claim.
     *
     * <p>Never throws on a duplicate — a re-dispatch is a legitimate, expected event whenever a
     * dispatch response was lost in flight.
     */
    public Claim claim(DocumentProcessingCommand command) {
        String key = command.envelope().idempotencyKey();

        try {
            var job = jobs.insert(new WorkerJob(
                    command.envelope().jobId(), key, command.documentVersionId(),
                    command.documentType().name(), command.sourceBucket(),
                    command.sourceObjectKey(), command.callbackUrl()));
            log.info("Claimed job {} for document version {}", job.getId(), job.getDocumentVersionId());
            return new Claim(job, true);

        } catch (DuplicateKeyException e) {
            // Already claimed. Return the existing record so the caller can answer 202 with
            // duplicate=true rather than starting the work again.
            var existing = jobs.findByIdempotencyKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate key " + key + " but no record found; the unique index and "
                                    + "the lookup disagree, which should be impossible"));
            log.info("Job for {} already claimed as {} in state {}; not restarting",
                    key, existing.getId(), existing.getState());
            return new Claim(existing, false);
        }
    }
}
