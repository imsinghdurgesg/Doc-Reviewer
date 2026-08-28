package com.app.docreview.documentprocessing.jobs;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Claim records for dispatched work (§21, §22.2). */
public interface WorkerJobRepository extends MongoRepository<WorkerJob, String> {

    Optional<WorkerJob> findByIdempotencyKey(String idempotencyKey);
}
