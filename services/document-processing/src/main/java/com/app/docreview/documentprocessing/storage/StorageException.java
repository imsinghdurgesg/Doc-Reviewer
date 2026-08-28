package com.app.docreview.documentprocessing.storage;

/**
 * An object-storage operation failed. Architecture §9.1.
 *
 * <p>Unchecked because no caller in the parsing pipeline can meaningfully recover from storage being
 * unavailable — the job fails, the sweep retries it later (§21).
 *
 * <p>Messages carry the bucket and key only, never content: they are logged (§35).
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
