package com.app.docreview.reviewcore.storage.minio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO connection settings. Architecture §23, §30.
 *
 * <p>A record, not a Lombok class: §25 prefers records for configuration, and immutability is
 * correct here — rebinding credentials at runtime is not a feature.
 *
 * <p>No {@code toString()} is generated, deliberately: these carry the secret key, and configuration
 * objects end up in startup logs and actuator output (§10.5, §34).
 */
@ConfigurationProperties(prefix = "docreview.storage.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
}
