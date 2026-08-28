package com.app.docreview.documentprocessing.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Cloud Storage settings for the parsing worker. Architecture §23, §41.
 *
 * <p>Deliberately a copy of review-core's record rather than a shared one: §6 keeps a service free of
 * another service's classes, and this needs only the bucket. The values come from the same
 * environment variables, so the two cannot drift in a deployment.
 *
 * <p>No credential field — authentication is Application Default Credentials, from a workload
 * identity or attached service account.
 */
@ConfigurationProperties(prefix = "docreview.storage.gcs")
public record GcsProperties(
        String projectId,
        String bucket
) {
}
