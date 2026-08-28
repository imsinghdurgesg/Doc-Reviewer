package com.app.docreview.reviewcore.workflow.domain;

import com.app.docreview.contracts.job.JobState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable job records (§21). */
public interface ProcessingJobRepository extends MongoRepository<ProcessingJob, String> {

    Optional<ProcessingJob> findByIdempotencyKey(String idempotencyKey);

    /**
     * Backs the recovery sweep (§21 step 8): jobs left in a non-terminal state past a threshold,
     * because review-core restarted between writing and dispatching, or a worker died mid-run.
     */
    List<ProcessingJob> findByStateInAndUpdatedAtBefore(List<JobState> states, Instant before);
}
