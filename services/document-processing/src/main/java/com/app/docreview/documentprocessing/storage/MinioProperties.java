package com.app.docreview.documentprocessing.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO connection settings. Architecture §23, §30.
 *
 * <p>Credentials come from the environment (§30) and are never defaulted to a working value: a
 * deployment with blank credentials should fail visibly rather than silently fall back to something
 * that happens to work locally.
 */
@ConfigurationProperties(prefix = "docreview.storage.minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey) {

    public void validate() {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("docreview.storage.minio.endpoint is not set.");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "MinIO credentials are not set; document sources cannot be read. "
                            + "Set MINIO_ACCESS_KEY and MINIO_SECRET_KEY.");
        }
    }
}
