package com.app.docreview.reviewcore.aiconfig;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Stores the single review-limits row. Architecture §10.5. */
public interface ReviewLimitsConfigRepository extends MongoRepository<ReviewLimitsConfig, String> {
}
