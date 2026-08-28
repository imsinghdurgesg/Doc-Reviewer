package com.app.docreview.reviewcore.storage;

import java.io.InputStream;

/**
 * Object storage port. Architecture §9.1, §23.
 *
 * <p>An interface because §41 anticipates replacing MinIO with GCS or S3 without touching service
 * boundaries. Application code depends on this; only the adapter knows the vendor SDK.
 *
 * <p>Binary documents live here and never in MongoDB (§22): document bodies are large, and putting
 * them in the metadata store makes every query and backup carry them.
 */
public interface ObjectStorage {

    /**
     * Stores an object and returns the key it was written to.
     *
     * @param bucket one of the buckets in §23
     * @param key    full object key, e.g. {@code active/{standardId}/{version}/source.pdf}
     */
    String put(String bucket, String key, byte[] content, String contentType);

    byte[] get(String bucket, String key);

    /**
     * Streams an object. Preferred over {@link #get} for anything served to a browser — a rendered
     * preview can be tens of megabytes, and buffering it defeats the point of streaming it out.
     */
    InputStream stream(String bucket, String key);

    boolean exists(String bucket, String key);

    void delete(String bucket, String key);
}
