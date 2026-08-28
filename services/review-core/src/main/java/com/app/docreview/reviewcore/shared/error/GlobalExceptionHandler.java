package com.app.docreview.reviewcore.shared.error;

import com.app.docreview.contracts.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Maps exceptions to the error contract. Architecture §21.
 *
 * <p>Every response carries a correlation ID that is also logged, so a user can report an error and
 * an operator can find the corresponding log line without the response having to leak internals.
 *
 * <p>Unexpected exceptions return a GENERIC message. The real cause is logged, never returned:
 * exception text from a parser or driver routinely contains fragments of the document being
 * processed (§34).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        String correlationId = UUID.randomUUID().toString();
        log.warn("API error [{}] {}: {}", correlationId, e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(
                e.getCode(), e.getMessage(), correlationId, Instant.now(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String correlationId = UUID.randomUUID().toString();
        // Field names and constraint messages only. Rejected VALUES are omitted: a validation error
        // on a document field would otherwise echo document content into logs and responses.
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        log.warn("Validation failure [{}]: {}", correlationId, details);
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "VALIDATION_FAILED", "The request was not valid.",
                correlationId, Instant.now(), details));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Storage failure [{}]", correlationId, e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                "STORAGE_UNAVAILABLE", "Document storage is unavailable. Please retry.",
                correlationId, Instant.now(), List.of()));
    }

    /**
     * Authorization denial. Architecture §33, §34.
     *
     * <p>Without this handler a {@code @PreAuthorize} rejection falls through to the generic 500
     * below, which tells the user "an unexpected error occurred" for what is in fact a correct,
     * expected authorization decision. That is actively misleading: it sends the reader looking for a
     * server fault instead of a permissions problem, and it hides a genuine 500 in the same noise.
     *
     * <p>Logged at WARN, not ERROR — a denial is the security layer working, not a malfunction. The
     * message names no role or resource: a 403 that explains which role would have succeeded is a
     * permissions oracle.
     */
    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDenied(
            org.springframework.security.authorization.AuthorizationDeniedException e) {
        String correlationId = UUID.randomUUID().toString();
        log.warn("Authorization denied [{}]: {}", correlationId, e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(
                "FORBIDDEN", "You do not have permission to perform this action.",
                correlationId, Instant.now(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [{}]", correlationId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "INTERNAL_ERROR", "An unexpected error occurred.",
                correlationId, Instant.now(), List.of()));
    }
}
