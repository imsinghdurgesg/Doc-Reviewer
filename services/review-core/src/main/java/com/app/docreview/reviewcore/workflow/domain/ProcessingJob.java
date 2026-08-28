package com.app.docreview.reviewcore.workflow.domain;

import com.app.docreview.contracts.job.JobState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * A durable unit of background work. Architecture §21.
 *
 * <p><strong>The record is written BEFORE dispatch</strong>, which is what makes this pattern durable
 * without a broker. If review-core dies between writing and dispatching, the recovery sweep finds a
 * PENDING job and re-dispatches it. If it dies after dispatching, the worker's idempotent callback
 * still resolves it. The failure mode that has no recovery is dispatching first and recording
 * after — the job would be invisible and its completion callback unattributable.
 *
 * <p>{@code idempotencyKey} is unique-indexed, so a retried dispatch cannot create a second job or
 * duplicate artifacts (§21). It is derived from the work itself, not generated per attempt —
 * otherwise every retry looks like new work.
 *
 * <p>{@code payload} holds object references and IDs only, never document text: job records are
 * queried, logged, and exported in diagnostics (§35).
 */
@Document(collection = "processing_jobs")
@CompoundIndex(name = "job_recovery", def = "{'state': 1, 'updatedAt': 1}")
@Getter
@NoArgsConstructor
public class ProcessingJob {

    @Id
    private String id;

    private String jobType;

    /**
     * Unique per logical unit of work. A repeated request with the same key must not create
     * duplicate artifacts or findings (§21).
     */
    @Indexed(unique = true)
    private String idempotencyKey;

    @Setter private JobState state;
    @Setter private int attempt;
    /** Bounded by policy; §18.4 requires retries to stop rather than loop. */
    private int maxAttempts;

    /** IDs and object keys only. Never document text, prompts, or credentials. */
    private Map<String, String> payload;
    private int payloadVersion;

    /** Progress for the UI (§21). A count plus a state, not free-form prose. */
    @Setter private String progressState;
    @Setter private Integer completedUnits;
    @Setter private Integer totalUnits;

    /** Failure reason, safe to display. Never a stack trace or document content. */
    @Setter private String failureReason;

    private Instant createdAt;
    @Setter private Instant updatedAt;
    @Setter private Instant dispatchedAt;
    @Setter private Instant claimedAt;
    @Setter private Instant completedAt;

    public ProcessingJob(String jobType, String idempotencyKey,
                         Map<String, String> payload, int payloadVersion, int maxAttempts) {
        this.jobType = jobType;
        this.idempotencyKey = idempotencyKey;
        this.payload = Map.copyOf(payload);
        this.payloadVersion = payloadVersion;
        this.maxAttempts = maxAttempts;
        this.state = JobState.PENDING;
        this.attempt = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** True when another attempt is permitted. Keeps the retry bound in one place. */
    public boolean retryable() {
        return attempt < maxAttempts;
    }
}
