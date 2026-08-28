package com.app.docreview.reviewcore.storage.gcs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Cloud Storage settings. Architecture §23, §30, §41.
 *
 * <p>A record for the same reasons as the MinIO properties: §25 prefers records for configuration,
 * and rebinding storage credentials at runtime is not a feature. No {@code toString()} is generated,
 * so nothing here reaches a startup log or an actuator endpoint (§10.5, §34).
 *
 * <p><strong>No credential fields.</strong> Authentication is Application Default Credentials —
 * a workload identity or attached service account in deployment, {@code gcloud auth
 * application-default login} locally. Accepting pasted service-account JSON would put a long-lived
 * private key in configuration, which is the credential most likely to be copied into a ticket or a
 * log; ADC has no such artifact to leak.
 *
 * @param projectId    optional; ADC usually supplies it, and an explicit value is only needed when
 *                     the credential is scoped to several projects
 * @param bucket       the single bucket holding every prefix in {@link
 *                     com.app.docreview.reviewcore.storage.StorageBuckets}. One bucket rather than
 *                     three because GCS bucket names are globally unique and IAM binds per bucket:
 *                     three buckets means three names to reserve and three policies to keep aligned
 * @param standardsPrefix   where synced policy documents are read from and written to
 */
@ConfigurationProperties(prefix = "docreview.storage.gcs")
public record GcsProperties(
        String projectId,
        String bucket,
        String standardsPrefix
) {

    /**
     * Applies the documented defaults.
     *
     * <p>The prefix is normalised to end with {@code /}. A prefix written {@code "standards"} rather
     * than {@code "standards/"} silently produces keys like {@code standardsactive/...}, which lists
     * cleanly under a prefix scan and is invisible until someone opens the bucket.
     */
    public GcsProperties {
        standardsPrefix = normalisePrefix(standardsPrefix, "standards/");
    }

    private static String normalisePrefix(String value, String fallback) {
        String prefix = value == null || value.isBlank() ? fallback : value.trim();
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
