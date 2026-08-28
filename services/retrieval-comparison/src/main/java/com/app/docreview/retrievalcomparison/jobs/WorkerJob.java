package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * The worker's claim on a dispatched job. Architecture §21 step 3, §22.3.
 *
 * <p>Covers BOTH job types this service handles — indexing (§20.1) and comparison (§20.2) — because
 * the claim semantics are identical and {@code jobType} already distinguishes them. Two near-identical
 * collections would duplicate the unique-index logic that makes claims safe.
 *
 * <p>{@code idempotencyKey} is unique-indexed, and that index IS the concurrency control: concurrent
 * dispatches race on insert and exactly one wins (§39 rule 23).
 */
@Document(collection = "indexing_jobs")
@Getter
@NoArgsConstructor
public class WorkerJob {

    @Id
    private String id;

    /** review-core's job ID, echoed on the callback so it can attribute the result. */
    private String originJobId;

    @Indexed(unique = true)
    private String idempotencyKey;

    private JobType jobType;

    // Indexing subject (§20.1).
    private String standardId;
    private String standardVersionId;
    private String standardTitle;

    /**
     * Descriptive metadata for the selection profile (§15.1). All optional.
     *
     * <p>Carried on the claim rather than fetched: §22 keeps review-core's standards collection
     * private, so the command is the only way these reach this service. A standard created before
     * they were captured indexes with them blank.
     */
    private String standardDescription;
    private String standardCategory;
    private String standardBusinessUnit;
    /** Family and governed document type — ranking signals for candidate selection (§15.1). */
    private String standardFamily;
    private String standardDocumentType;
    private java.util.List<String> standardTags;
    private String chunkingStrategyVersion;

    // Comparison subject (§20.2).
    private String comparisonId;
    private String documentVersionId;
    /** Empty means automatic document-level standard selection (§15.1). */
    private List<String> standardVersionIds;
    private int maxAutomaticStandards;
    /**
     * Reviewer assessment context, or null. Steers retrieval focus and ranking; never a filter (§15.1).
     */
    private String guidance;

    /**
     * The document's type, inferred once per job. Null when it could not be determined.
     *
     * <p>Resolved ONCE and reused for every requirement rather than per requirement: the answer is a
     * property of the document, so recomputing it 200 times would pay for the same inference 200 times
     * and risk 200 different answers. Used only to resolve stated applicability conditions (§12.5).
     */
    @Setter private String documentType;

    private String callbackUrl;

    @Setter private JobState state;
    @Setter private String progressState;
    @Setter private Integer completedUnits;
    @Setter private Integer totalUnits;

    /** Display-safe. Never a stack trace, prompt, or document content (§34, §10.5). */
    @Setter private String failureReason;

    @Setter private boolean callbackDelivered;

    private Instant claimedAt;
    @Setter private Instant updatedAt;
    @Setter private Instant completedAt;

    /** Creates an indexing claim (§20.1). */
    public static WorkerJob forIndexing(String originJobId, String idempotencyKey, String standardId,
                                        String standardVersionId, String standardTitle,
                                        String standardDescription, String standardCategory,
                                        String standardBusinessUnit, String standardFamily,
                                        String standardDocumentType, java.util.List<String> standardTags,
                                        String chunkingStrategyVersion, String callbackUrl) {
        var job = new WorkerJob(originJobId, idempotencyKey, JobType.STANDARD_INDEXING, callbackUrl);
        job.standardId = standardId;
        job.standardVersionId = standardVersionId;
        job.standardTitle = standardTitle;
        job.standardDescription = standardDescription;
        job.standardCategory = standardCategory;
        job.standardBusinessUnit = standardBusinessUnit;
        job.standardFamily = standardFamily;
        job.standardDocumentType = standardDocumentType;
        job.standardTags = standardTags == null ? java.util.List.of() : java.util.List.copyOf(standardTags);
        job.chunkingStrategyVersion = chunkingStrategyVersion;
        return job;
    }

    /** Creates a comparison claim (§20.2). */
    public static WorkerJob forComparison(String originJobId, String idempotencyKey,
                                          String comparisonId, String documentVersionId,
                                          List<String> standardVersionIds,
                                          int maxAutomaticStandards, String guidance,
                                          String callbackUrl) {
        var job = new WorkerJob(originJobId, idempotencyKey, JobType.RETRIEVAL_COMPARISON, callbackUrl);
        job.comparisonId = comparisonId;
        job.documentVersionId = documentVersionId;
        job.standardVersionIds = List.copyOf(standardVersionIds);
        job.maxAutomaticStandards = maxAutomaticStandards;
        job.guidance = guidance;
        return job;
    }

    private WorkerJob(String originJobId, String idempotencyKey, JobType jobType, String callbackUrl) {
        this.originJobId = originJobId;
        this.idempotencyKey = idempotencyKey;
        this.jobType = jobType;
        this.callbackUrl = callbackUrl;
        this.state = JobState.RUNNING;
        this.standardVersionIds = List.of();
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
