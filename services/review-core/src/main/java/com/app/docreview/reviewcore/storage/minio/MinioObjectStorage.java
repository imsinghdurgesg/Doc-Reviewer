package com.app.docreview.reviewcore.storage.minio;

import com.app.docreview.reviewcore.shared.error.StorageException;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO implementation of {@link ObjectStorage}. Architecture §9.1.
 *
 * <p>The only class that knows the MinIO SDK. Everything else depends on the port, so §41's
 * "replace individual adapters" is a real option rather than an aspiration.
 */
@Component
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "docreview.storage.provider", havingValue = "minio", matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;

    public MinioObjectStorage(MinioProperties properties) {
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public String put(String bucket, String key, byte[] content, String contentType) {
        ensureBucket(bucket);
        try (InputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    // -1 partSize with a known objectSize lets the SDK pick the part size itself.
                    .stream(in, content.length, -1)
                    .contentType(contentType)
                    .build());
            // Key and size only — never the content, which is a confidential document (§35).
            log.debug("Stored object bucket={} key={} bytes={}", bucket, key, content.length);
            return key;
        } catch (Exception e) {
            throw new StorageException("Failed to store object " + bucket + "/" + key, e);
        }
    }

    @Override
    public byte[] get(String bucket, String key) {
        try (InputStream in = stream(bucket, key)) {
            return in.readAllBytes();
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to read object " + bucket + "/" + key, e);
        }
    }

    @Override
    public InputStream stream(String bucket, String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Failed to open object " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            // A missing object is a normal answer to this question, not a failure. Any OTHER
            // error-response code is a real problem and must not be reported as "absent".
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw new StorageException("Failed to stat object " + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new StorageException("Failed to stat object " + bucket + "/" + key, e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Failed to delete object " + bucket + "/" + key, e);
        }
    }

    /**
     * Creates the bucket when absent.
     *
     * <p>§23 says buckets are created by {@code minio-init}. This is a safety net for local runs
     * where that init step was skipped — failing an upload because a bucket is missing is a poor
     * first experience, and bucket creation is idempotent.
     */
    private void ensureBucket(String bucket) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created missing bucket {}", bucket);
            }
        } catch (Exception e) {
            throw new StorageException("Failed to ensure bucket " + bucket, e);
        }
    }
}
