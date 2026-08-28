package com.app.docreview.retrievalcomparison.jobs;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Claim records for dispatched indexing and comparison work (§21, §22.3). */
public interface WorkerJobRepository extends MongoRepository<WorkerJob, String> {

    Optional<WorkerJob> findByIdempotencyKey(String idempotencyKey);

    /**
     * The job that ran a comparison, for a targeted retry.
     *
     * <p>A retry needs the ORIGINAL job's document, standards and resolved document type — reconstructing
     * them from anywhere else risks retrying a requirement under different inputs than the run that
     * failed it, which would make the amended finding incomparable with its neighbours.
     */
    Optional<WorkerJob> findFirstByComparisonIdOrderByClaimedAtDesc(String comparisonId);
}
