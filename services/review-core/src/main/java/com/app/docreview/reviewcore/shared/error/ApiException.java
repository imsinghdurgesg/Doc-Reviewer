package com.app.docreview.reviewcore.shared.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An error with a stable code and an HTTP status. Architecture §21 (error model).
 *
 * <p>{@code code} is what the UI branches on; the message is for humans and may be reworded. Callers
 * that branch on message text break on copy edits.
 *
 * <p>Messages must never carry document text — they are logged and returned to the browser (§34).
 */
@Getter
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static ApiException notFound(String what) {
        return new ApiException("NOT_FOUND", what + " not found.", HttpStatus.NOT_FOUND);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(code, message, HttpStatus.CONFLICT);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, message, HttpStatus.BAD_REQUEST);
    }
}
