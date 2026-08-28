package com.app.docreview.documentprocessing.storage;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Google Cloud Storage implementation of {@link ObjectStorage}. Architecture §9.1, §23, §41.
 *
 * <p>The parsing worker's half of the storage decision. Both services must resolve a bucket and key
 * to the SAME object or nothing works: review-core writes an uploaded standard and this service
 * reads it back, so a deployment where only one of them speaks GCS fails every parse with
 * {@code NoSuchKey} — the object is written to one store and looked for in another.
 *
 * <p><strong>The key mapping is deliberately identical to review-core's.</strong> The logical bucket
 * becomes the leading path segment ({@code docreview-standards/active/...}), so the two services
 * address the same object without sharing code. Diverging here would produce a subtler version of the
 * same bug: writes and reads that both succeed, against different objects.
 *
 * <p><strong>The parser is unaffected.</strong> {@link ObjectStorage#get} returns {@code byte[]}, and
 * PDFBox and POI parse bytes regardless of origin — so no parsing code changes for GCS.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "docreview.storage.provider", havingValue = "gcs")
public class GcsObjectStorage implements ObjectStorage {

    private final Storage storage;
    private final GcsProperties properties;
    private final ActiveBucketResolver bucketResolver;

    public GcsObjectStorage(GcsProperties properties, ActiveBucketResolver bucketResolver) {
        this.properties = properties;
        this.bucketResolver = bucketResolver;
        // Not required at startup: the bucket may come entirely from review-core, which serves the
        // Admin-configured value. Refusing to boot without an environment value would make an
        // Admin-only configuration impossible.
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            log.info("No GCS bucket in configuration; expecting one from review-core.");
        }
        var builder = StorageOptions.newBuilder();
        if (properties.projectId() != null && !properties.projectId().isBlank()) {
            builder.setProjectId(properties.projectId());
        }
        this.storage = builder.build().getService();
        log.info("GCS object storage active: configuredBucket={}",
                properties.bucket() == null || properties.bucket().isBlank() ? "(from review-core)" : properties.bucket());
    }

    /** Must match review-core's mapping exactly; see the class comment. */
    private String objectName(String bucket, String key) {
        return bucket + "/" + key;
    }

    /**
     * The GCS bucket to act on, resolved per call.
     *
     * <p>Asks review-core, because an administrator can repoint the bucket from the Admin screen and
     * this service must follow. Falls back to the configured value — see {@link ActiveBucketResolver}.
     */
    private String activeBucket() {
        String bucket = bucketResolver.resolve(properties.bucket());
        if (bucket == null || bucket.isBlank()) {
            // Neither review-core nor local configuration named one. Actionable rather than a null
            // bucket name reaching the GCS client.
            throw new StorageException(
                    "No GCS bucket is configured. Set one under Admin -> Cloud sync, or supply "
                            + "GCS_BUCKET.", null);
        }
        return bucket;
    }

    @Override
    public byte[] get(String bucket, String key) {
        String name = objectName(bucket, key);
        try {
            byte[] content = storage.readAllBytes(BlobId.of(activeBucket(), name));
            if (content == null) {
                throw new StorageException("Object not found: " + name, null);
            }
            return content;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to read object " + name, e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            var blob = storage.get(BlobId.of(activeBucket(), objectName(bucket, key)));
            return blob != null && blob.exists();
        } catch (Exception e) {
            // Reported as absent rather than propagated: callers use this to decide whether to write,
            // and a transient lookup failure should not abort work that would otherwise succeed.
            log.warn("Existence check failed for {}/{}: {}", bucket, key, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String put(String bucket, String key, byte[] content, String contentType) {
        String name = objectName(bucket, key);
        try {
            var blobInfo = BlobInfo.newBuilder(BlobId.of(activeBucket(), name))
                    .setContentType(contentType == null ? "application/octet-stream" : contentType)
                    .build();
            storage.create(blobInfo, content);
            // Key and size only — never the content, which is a confidential document (§35).
            log.debug("Stored object gs://{}/{} bytes={}", activeBucket(), name, content.length);
            return key;
        } catch (Exception e) {
            throw new StorageException("Failed to store object " + name, e);
        }
    }
}
