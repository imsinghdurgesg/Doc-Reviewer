package com.app.docreview.documentprocessing.storage;

/**
 * Object storage port. Architecture §9.1, §23.
 *
 * <p>Declared per service rather than shared, because §39 rule 10 forbids depending on another
 * service's implementation JAR and §6 keeps {@code platform-contracts} free of infrastructure. The
 * duplication is deliberate: each service owns the operations it actually needs, and this one reads
 * sources and writes canonical artifacts (§8.2) — it never deletes or signs URLs.
 */
public interface ObjectStorage {

    byte[] get(String bucket, String key);

    /** Stores an artifact and returns the key. Used for canonical JSON and rendered previews (§23). */
    String put(String bucket, String key, byte[] content, String contentType);

    boolean exists(String bucket, String key);
}
