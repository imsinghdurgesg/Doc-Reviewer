package com.app.docreview.reviewcore.storage.gcs;

import com.app.docreview.reviewcore.shared.error.StorageException;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.channels.Channels;

/**
 * Google Cloud Storage implementation of {@link ObjectStorage}. Architecture §9.1, §41.
 *
 * <p>The only class that knows the GCS SDK, mirroring {@code MinioObjectStorage}. Selected by
 * {@code docreview.storage.provider=gcs}; MinIO remains the default so local development and the
 * Compose stack are unaffected.
 *
 * <h2>One bucket, prefixed</h2>
 *
 * <p>The port's {@code bucket} argument is one of three logical names from {@code StorageBuckets}.
 * GCS bucket names are globally unique and IAM binds per bucket, so three real buckets would mean
 * three names to reserve and three policies to keep aligned. They are mapped to prefixes inside a
 * single configured bucket instead — the key layout is preserved exactly, so an object written by
 * either adapter is readable by the other after a copy.
 *
 * <h2>Authentication</h2>
 *
 * <p>Application Default Credentials only. In deployment that is a workload identity or an attached
 * service account; locally, {@code gcloud auth application-default login}. There is deliberately no
 * option to supply service-account JSON: a downloaded private key is the credential most likely to
 * end up in a ticket, a log, or a container image.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "docreview.storage.provider", havingValue = "gcs")
public class GcsObjectStorage implements ObjectStorage {

    private final Storage storage;
    private final GcsProperties properties;
    /**
     * Admin-configured settings, consulted per call.
     *
     * <p>ObjectProvider rather than a direct dependency so this adapter can still be constructed in a
     * context where the config service is absent — and so a future ordering change cannot turn a
     * missing bean into a startup failure for the whole storage layer.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService> syncConfig;

    public GcsObjectStorage(
            GcsProperties properties,
            org.springframework.beans.factory.ObjectProvider<
                    com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService> syncConfig) {
        this.properties = properties;
        this.syncConfig = syncConfig;
        // Deliberately NOT required at startup. The bucket can be supplied entirely from the Admin
        // screen, which is the whole point of it being configurable — refusing to boot without an
        // environment value would mean a deployment could never reach the screen that configures it.
        //
        // The cost is that a missing bucket surfaces on the first object operation instead of at
        // boot, so `activeBucket()` names the fix rather than letting the GCS client fail with a
        // "bucket null" that reads like an internal error.
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            log.info("No GCS bucket in configuration; expecting one from the Admin screen.");
        }
        var builder = StorageOptions.newBuilder();
        if (properties.projectId() != null && !properties.projectId().isBlank()) {
            builder.setProjectId(properties.projectId());
        }
        this.storage = builder.build().getService();
        log.info("GCS object storage active: bucket={} standardsPrefix={}",
                properties.bucket(), properties.standardsPrefix());
    }

    /**
     * Maps a logical bucket and key onto an object name in the configured bucket.
     *
     * <p>The logical bucket becomes the leading path segment, so {@code docreview-standards} +
     * {@code active/S1/2.0/x.pdf} becomes {@code docreview-standards/active/S1/2.0/x.pdf}. Keeping
     * the logical name in the path rather than dropping it means the three stores cannot collide on
     * a shared key, and the bucket remains readable by a human looking for a specific artifact.
     */
    private String objectName(String bucket, String key) {
        return bucket + "/" + key;
    }

    /**
     * The bucket to act on, resolved per call.
     *
     * <p><strong>Admin wins over the environment.</strong> The bucket sync already reads from the
     * Admin-configured bucket, so an adapter fixed to the environment value would read from one
     * bucket and write to another — canonical artifacts landing where nothing looks for them, and
     * every document pane 404ing while the sync reported success.
     *
     * <p>Resolved per call rather than cached so a change takes effect without a restart, which is
     * the point of configuring it from a screen. A stale read costs one wrong-bucket write; the
     * lookup is a single indexed find.
     */
    private String activeBucket() {
        var service = syncConfig.getIfAvailable();
        if (service != null) {
            var configured = service.current().getBucket();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            // Actionable, because the reader can fix it: neither source has a bucket. The GCS client
            // would otherwise fail on a null name with a message naming neither cause.
            throw new StorageException(
                    "No GCS bucket is configured. Set one under Admin -> Cloud sync, or supply "
                            + "GCS_BUCKET.", null);
        }
        return properties.bucket();
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
    public InputStream stream(String bucket, String key) {
        String name = objectName(bucket, key);
        try {
            Blob blob = storage.get(BlobId.of(activeBucket(), name));
            if (blob == null) {
                throw new StorageException("Object not found: " + name, null);
            }
            // A ReadChannel rather than readAllBytes: a rendered preview can be tens of megabytes,
            // and the port exists so the caller can stream it out without buffering (§9.1).
            return Channels.newInputStream(blob.reader());
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to stream object " + name, e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            Blob blob = storage.get(BlobId.of(activeBucket(), objectName(bucket, key)));
            return blob != null && blob.exists();
        } catch (Exception e) {
            // Reported as absent rather than propagated: callers use this to decide whether to
            // write, and a transient lookup failure should not abort an upload that would succeed.
            log.warn("Existence check failed for {}/{}: {}", bucket, key, e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void delete(String bucket, String key) {
        String name = objectName(bucket, key);
        try {
            storage.delete(BlobId.of(activeBucket(), name));
        } catch (Exception e) {
            throw new StorageException("Failed to delete object " + name, e);
        }
    }

    /**
     * The underlying client, for the bucket sync.
     *
     * <p>Exposed because reconciliation needs to LIST a prefix, which the port deliberately does not
     * offer: every other caller addresses one object by key, and adding a list operation to the port
     * would oblige the MinIO adapter to implement a capability nothing else uses. The sync is already
     * GCS-specific — it exists to reconcile a GCS bucket — so it depends on this adapter directly
     * rather than widening the abstraction for one consumer.
     */
    public Storage storageClient() {
        return storage;
    }

    /** Where an object with this logical bucket and key lives, for logging and diagnostics. */
    public String uriOf(String bucket, String key) {
        return "gs://%s/%s".formatted(activeBucket(), objectName(bucket, key));
    }
}
