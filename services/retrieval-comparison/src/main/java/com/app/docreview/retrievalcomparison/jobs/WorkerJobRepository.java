package com.app.docreview.retrievalcomparison.jobs;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Claim records for dispatched indexing and comparison work (§21, §22.3). */
public interface WorkerJobRepository extends MongoRepository<WorkerJob, String> {

    Optional<WorkerJob> findByIdempotencyKey(String idempotencyKey);
}
