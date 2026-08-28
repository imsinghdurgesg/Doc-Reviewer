package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.job.JobState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The worker's own record of a claimed job. Architecture §21 step 3, §22.2.
 *
 * <p>Separate from review-core's {@code ProcessingJob} because the two answer different questions
 * and are owned by different services (§22). review-core's record is the orchestration state the
 * user sees; this one is the claim — the thing that makes a repeated dispatch a no-op.
 *
 * <p>{@code idempotencyKey} is unique-indexed, and that index IS the concurrency control. Two
 * simultaneous dispatches of the same job both attempt an insert; exactly one succeeds and the other
 * gets a duplicate-key error. Checking "does it exist?" and then inserting would leave a window
 * between the two where both callers see nothing and both proceed (§21, §39 rule 23).
 */
@Document(collection = "document_processing_jobs")
@Getter
@NoArgsConstructor
public class WorkerJob {

    @Id
    private String id;

    /** review-core's job ID, echoed on the callback so it can attribute the result. */
    private String originJobId;

    @Indexed(unique = true)
    private String idempotencyKey;

    private String documentVersionId;
    private String documentType;
    private String sourceBucket;
    private String sourceObjectKey;
    private String callbackUrl;

    @Setter private JobState state;
    @Setter private String progressState;
    @Setter private Integer completedUnits;
    @Setter private Integer totalUnits;

    /** Display-safe. Never a stack trace or document content (§34). */
    @Setter private String failureReason;

    /** True once the completion callback has been acknowledged, so it is not resent forever. */
    @Setter private boolean callbackDelivered;

    private Instant claimedAt;
    @Setter private Instant updatedAt;
    @Setter private Instant completedAt;

    public WorkerJob(String originJobId, String idempotencyKey, String documentVersionId,
                     String documentType, String sourceBucket, String sourceObjectKey,
                     String callbackUrl) {
        this.originJobId = originJobId;
        this.idempotencyKey = idempotencyKey;
        this.documentVersionId = documentVersionId;
        this.documentType = documentType;
        this.sourceBucket = sourceBucket;
        this.sourceObjectKey = sourceObjectKey;
        this.callbackUrl = callbackUrl;
        this.state = JobState.RUNNING;
        this.claimedAt = Instant.now();
        this.updatedAt = this.claimedAt;
    }

    public boolean terminal() {
        return state == JobState.COMPLETED
                || state == JobState.PARTIALLY_COMPLETED
                || state == JobState.FAILED
                || state == JobState.CANCELLED;
    }
}
