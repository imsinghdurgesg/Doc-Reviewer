package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.job.JobAcceptedResponse;
import com.app.docreview.contracts.job.RetrievalComparisonCommand;
import com.app.docreview.contracts.job.StandardIndexingCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Claims dispatched jobs exactly once. Architecture §21 step 3, §39 rule 23.
 *
 * <p>The claim is an INSERT relying on the unique index on {@code idempotencyKey}, not a
 * read-then-write: concurrent dispatches race and exactly one wins. A check-then-insert leaves a
 * window where both callers see nothing and both proceed.
 *
 * <p>Duplicate suppression matters more in this service than anywhere else. A duplicated indexing job
 * publishes a second requirement-set version and comparisons silently split across two sets of
 * controls (§12.9); a duplicated comparison job doubles the LLM spend for one upload (§10.5).
 *
 * <p>{@link DuplicateKeyException} is therefore the SUCCESS path for a re-dispatch, not an error.
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

    public Claim claim(StandardIndexingCommand command) {
        var envelope = command.envelope();
        return claim(envelope.idempotencyKey(), () -> WorkerJob.forIndexing(
                envelope.jobId(), envelope.idempotencyKey(), command.standardId(),
                command.standardVersionId(), command.standardTitle(),
                command.standardDescription(), command.standardCategory(),
                command.standardBusinessUnit(), command.standardFamily(),
                command.standardDocumentType(), command.standardTags(),
                command.chunkingStrategyVersion(), command.callbackUrl()));
    }

    public Claim claim(RetrievalComparisonCommand command) {
        var envelope = command.envelope();
        return claim(envelope.idempotencyKey(), () -> WorkerJob.forComparison(
                envelope.jobId(), envelope.idempotencyKey(), command.comparisonId(),
                command.documentVersionId(), command.standardVersionIds(),
                command.maxAutomaticStandards(), command.guidance(), command.callbackUrl()));
    }

    private Claim claim(String idempotencyKey, Supplier<WorkerJob> factory) {
        try {
            var job = jobs.insert(factory.get());
            log.info("Claimed {} job {}", job.getJobType(), job.getId());
            return new Claim(job, true);

        } catch (DuplicateKeyException e) {
            var existing = jobs.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Duplicate key " + idempotencyKey + " but no record found; the unique "
                                    + "index and the lookup disagree, which should be impossible"));
            log.info("Job for {} already claimed as {} in state {}; not restarting",
                    idempotencyKey, existing.getId(), existing.getState());
            return new Claim(existing, false);
        }
    }
}
