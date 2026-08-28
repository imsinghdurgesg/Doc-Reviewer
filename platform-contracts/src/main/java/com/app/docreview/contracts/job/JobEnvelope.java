package com.app.docreview.contracts.job;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * The fields every internal job request must carry. Architecture §21 (idempotency).
 *
 * <p>Factored into its own record rather than repeated across the three command types, because §21
 * requires all of them and a command that omits one is not dispatchable. Composition also keeps the
 * worker's claim logic generic: it reads the envelope and does not care which command wraps it.
 *
 * <p>{@code idempotencyKey} is derived from the work itself — not generated per attempt — so a
 * retried dispatch is recognisable as the same job. See {@link #of} for the derivation.
 *
 * <p>{@code attempt} is informational for the worker and diagnostics. It does NOT participate in the
 * key: an attempt-scoped key would make every retry look like new work, which is the exact duplicate
 * -artifact failure §21 exists to prevent.
 */
public record JobEnvelope(

        @NotBlank String jobId,

        @NotNull JobType jobType,

        @Min(0) int attempt,

        @NotBlank String idempotencyKey,

        @NotNull Instant createdAt,

        /** Bumped when the payload shape changes, so a worker can reject what it cannot read. */
        @Min(1) int payloadVersion) {

    /** Current payload shape. Increment on any breaking change to a command record. */
    public static final int CURRENT_PAYLOAD_VERSION = 1;

    /**
     * Builds an envelope with a key derived from the job type and its subject.
     *
     * <p>The subject is the immutable thing being worked on — a document version ID, a standard
     * version ID, or a comparison ID. Two dispatches naming the same type and subject are the same
     * logical work, which is precisely what must not produce two sets of artifacts.
     */
    public static JobEnvelope of(String jobId, JobType jobType, String subject, int attempt,
                                 Instant createdAt) {
        return new JobEnvelope(jobId, jobType, attempt,
                jobType.name() + ":" + subject, createdAt, CURRENT_PAYLOAD_VERSION);
    }
}
