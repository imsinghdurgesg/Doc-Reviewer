package com.app.docreview.contracts.error;

import java.time.Instant;
import java.util.List;

/**
 * Machine-readable API error. Architecture §21 (error model).
 *
 * <p>{@code code} is a stable enumerated string the UI can branch on; {@code message} is for humans
 * and may be reworded freely. Branching on message text is what makes error handling break on
 * copy edits.
 *
 * <p>Must never carry document text or model prose — errors are logged and forwarded widely, and
 * uploaded documents are confidential (§34).
 */
public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        Instant timestamp,
        List<String> details
) {}
