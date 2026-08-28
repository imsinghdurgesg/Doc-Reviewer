package com.app.docreview.reviewcore.aiconfig;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Single-document store for {@link AiProviderConfig}. */
public interface AiProviderConfigRepository extends MongoRepository<AiProviderConfig, String> {
}
