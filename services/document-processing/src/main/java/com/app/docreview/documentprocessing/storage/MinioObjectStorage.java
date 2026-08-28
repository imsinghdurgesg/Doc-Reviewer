package com.app.docreview.documentprocessing.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO implementation of {@link ObjectStorage}. Architecture §9.1.
 *
 * <p>The only class in this service that knows the MinIO SDK, so §41's "replace individual adapters"
 * stays a real option.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "docreview.storage.provider", havingValue = "minio", matchIfMissing = true)
@Slf4j
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;

    public MinioObjectStorage(MinioProperties properties) {
        properties.validate();
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public byte[] get(String bucket, String key) {
        try (InputStream in = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new StorageException("Failed to read object " + bucket + "/" + key, e);
        }
    }

    @Override
    public String put(String bucket, String key, byte[] content, String contentType) {
        ensureBucket(bucket);
        try (InputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
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
    public boolean exists(String bucket, String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            // A missing object is a normal answer here. Any OTHER error code is a real failure and
            // must not be reported as "absent".
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw new StorageException("Failed to stat object " + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new StorageException("Failed to stat object " + bucket + "/" + key, e);
        }
    }

    /** Safety net for local runs where {@code minio-init} (§23) was skipped. Idempotent. */
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
