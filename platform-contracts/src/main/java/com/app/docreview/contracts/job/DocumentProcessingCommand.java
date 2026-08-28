package com.app.docreview.contracts.job;

import com.app.docreview.contracts.document.DocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Dispatches parsing and rendering of one document version. Architecture §9.2, §20.3.
 *
 * <p>Carries an object REFERENCE, never the bytes (§20.3). A job request that embedded the document
 * would be megabytes wide, would land in request logs, and would defeat the durable-record pattern:
 * the record is the source of truth, and a payload that cannot be persisted cheaply cannot be
 * replayed by the recovery sweep (§21).
 *
 * <p>{@code documentType} selects the pipeline: a STANDARD is parsed to feed requirement extraction,
 * a PROCESS document is parsed to feed evidence retrieval (§14). The parsing itself is identical,
 * which is why one command covers both.
 */
public record DocumentProcessingCommand(

        @NotNull @Valid JobEnvelope envelope,

        /** The version whose canonical nodes, spans, and chunks this job produces. */
        @NotBlank String documentVersionId,

        @NotNull DocumentType documentType,

        /** Bucket holding the source object (§23). */
        @NotBlank String sourceBucket,

        @NotBlank String sourceObjectKey,

        /** Declared media type. The worker still verifies the file signature (§8.2). */
        String sourceMediaType,

        /**
         * Where the worker reports completion. Supplied by the dispatcher rather than configured in
         * the worker so a worker needs no knowledge of review-core's routes (§20.3).
         */
        @NotBlank String callbackUrl) {
}
