package com.app.docreview.reviewcore.shared.error;

/**
 * Object-storage failure. Architecture §9.1.
 *
 * <p>Separate from {@link ApiException} because storage failures are infrastructure faults, not
 * client errors: they map to 5xx and should be retried or alerted on, not shown to a user as
 * something they did wrong.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
