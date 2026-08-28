package com.app.docreview.reviewcore.aiconfig;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Single-document store for {@link CloudSyncConfig}. */
public interface CloudSyncConfigRepository extends MongoRepository<CloudSyncConfig, String> {
}
