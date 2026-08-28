package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.contracts.job.DocumentProcessingCommand;
import com.app.docreview.contracts.job.JobAcceptedResponse;
import com.app.docreview.contracts.job.JobEnvelope;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.contracts.job.RetrievalComparisonCommand;
import com.app.docreview.contracts.job.StandardIndexingCommand;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJob;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal HTTP job dispatch. Architecture §9.2, §21.
 *
 * <p>The ordering in {@link #dispatch} is the whole design, and it is deliberately not the obvious
 * one. Each dispatch:
 *
 * <ol>
 *   <li>Resolves the idempotency key and returns the EXISTING job if one is found. A double-submitted
 *       upload must not produce two sets of artifacts (§21).</li>
 *   <li>Writes the job record as PENDING — <strong>before</strong> any HTTP call.</li>
 *   <li>Posts to the worker.</li>
 *   <li>Marks the job DISPATCHED on 202.</li>
 * </ol>
 *
 * <p>Steps 2 and 3 are in that order because the alternative has no recovery. If review-core dies
 * after dispatching but before recording, the job is invisible: nothing re-dispatches it and the
 * worker's callback names a job that does not exist. Recording first makes the crash window
 * recoverable — the sweep finds a PENDING job and dispatches it again (§21 step 8).
 *
 * <p><strong>A dispatch failure does not fail the caller.</strong> The job is already durable, so a
 * worker that is down means the upload still succeeded and the work is pending. Propagating the
 * error would tell the user their upload failed when it did not.
 */
@Component
@Slf4j
public class HttpJobDispatcher implements JobDispatcher {

    private final ProcessingJobRepository jobs;
    private final JobDispatchProperties properties;
    private final RestClient documentProcessingClient;
    private final RestClient retrievalComparisonClient;

    public HttpJobDispatcher(ProcessingJobRepository jobs,
                             JobDispatchProperties properties,
                             InternalRestClientFactory clientFactory) {
        properties.validate();
        this.jobs = jobs;
        this.properties = properties;
        this.documentProcessingClient =
                clientFactory.create(properties.documentProcessing().baseUrl());
        this.retrievalComparisonClient =
                clientFactory.create(properties.retrievalComparison().baseUrl());
    }

    @Override
    public String dispatchDocumentProcessing(String documentVersionId, DocumentType documentType,
                                             String sourceBucket, String sourceObjectKey,
                                             String sourceMediaType) {
        var payload = new LinkedHashMap<String, String>();
        payload.put("documentVersionId", documentVersionId);
        payload.put("documentType", documentType.name());
        payload.put("sourceBucket", sourceBucket);
        payload.put("sourceObjectKey", sourceObjectKey);

        return dispatch(JobType.DOCUMENT_PROCESSING, documentVersionId, payload, job ->
                documentProcessingClient.post()
                        .uri("/internal/v1/document-processing/jobs")
                        .body(new DocumentProcessingCommand(
                                envelopeFor(job), documentVersionId, documentType,
                                sourceBucket, sourceObjectKey, sourceMediaType,
                                callbackUrl("document-processing")))
                        .retrieve()
                        .body(JobAcceptedResponse.class));
    }

    @Override
    public String dispatchStandardIndexing(String standardId, String standardVersionId,
                                           String standardTitle, String chunkingStrategyVersion,
                                           StandardProfile profile) {
        var payload = new LinkedHashMap<String, String>();
        payload.put("standardId", standardId);
        payload.put("standardVersionId", standardVersionId);
        // Stored so a re-dispatch can rebuild the same profile. The payload is a flat string map, so
        // tags are joined and split back — a nested value would not survive it.
        putIfPresent(payload, "standardDescription", profile.description());
        putIfPresent(payload, "standardCategory", profile.category());
        putIfPresent(payload, "standardBusinessUnit", profile.businessUnit());
        putIfPresent(payload, "standardFamily", profile.standardFamily());
        putIfPresent(payload, "standardDocumentType", profile.documentType());
        putIfPresent(payload, "standardTags",
                profile.tags() == null || profile.tags().isEmpty()
                        ? null : String.join(",", profile.tags()));
        payload.put("chunkingStrategyVersion", chunkingStrategyVersion);
        // Stored so a sweep re-dispatch can rebuild the command. A title is metadata, not document
        // body, so it does not violate the "references only" rule (§20.3).
        payload.put("standardTitle", standardTitle == null ? "" : standardTitle);

        return dispatch(JobType.STANDARD_INDEXING, standardVersionId, payload, job ->
                retrievalComparisonClient.post()
                        .uri("/internal/v1/standard-indexing/jobs")
                        .body(new StandardIndexingCommand(
                                envelopeFor(job), standardId, standardVersionId, standardTitle,
                                profile.description(), profile.category(), profile.businessUnit(),
                                profile.standardFamily(), profile.documentType(),
                                profile.tags(),
                                chunkingStrategyVersion, callbackUrl("standard-indexing")))
                        .retrieve()
                        .body(JobAcceptedResponse.class));
    }

    @Override
    public String dispatchRetrievalComparison(String comparisonId, String documentVersionId,
                                              List<String> standardVersionIds, String guidance) {
        var selected = standardVersionIds == null ? List.<String>of() : List.copyOf(standardVersionIds);

        var payload = new LinkedHashMap<String, String>();
        payload.put("comparisonId", comparisonId);
        payload.put("documentVersionId", documentVersionId);
        // Stored as a joined string: the payload is Map<String,String> so it stays cheap to persist
        // and log. The authoritative list travels in the command.
        payload.put("standardVersionIds", String.join(",", selected));
        // Persisted so a recovery sweep re-dispatches with the SAME guidance. Rebuilding the command
        // without it would silently re-run under a different retrieval focus than the reviewer asked
        // for, and §19.2 requires a replayed job to reproduce the original run.
        //
        // Safe despite §35: the payload is only ever read by key (never logged), and the identical text
        // is already stored on the Comparison in this same database, so this adds no new exposure.
        // LinkedHashMap permits null values but Mongo would round-trip it as a present null key, so
        // absent guidance is simply not put.
        if (guidance != null && !guidance.isBlank()) {
            payload.put("comparisonGuidance", guidance);
        }

        return dispatch(JobType.RETRIEVAL_COMPARISON, comparisonId, payload, job ->
                retrievalComparisonClient.post()
                        .uri("/internal/v1/comparison/jobs")
                        .body(new RetrievalComparisonCommand(
                                envelopeFor(job), comparisonId, documentVersionId, selected,
                                DEFAULT_MAX_AUTOMATIC_STANDARDS, guidance,
                                callbackUrl("comparison")))
                        .retrieve()
                        .body(JobAcceptedResponse.class));
    }

    /**
     * Default cap on automatically selected standards (§15.1).
     *
     * <p>Bounded because every added standard multiplies per-requirement model calls (§15.5). Five
     * keeps a demo comparison to a few hundred calls rather than a few thousand.
     */
    private static final int DEFAULT_MAX_AUTOMATIC_STANDARDS = 5;

    /** Posts a job to a worker and returns its acceptance, or throws. */
    private interface WorkerCall {
        JobAcceptedResponse post(ProcessingJob job);
    }

    /**
     * Re-sends an existing job to its worker, rebuilding the command from the persisted payload.
     *
     * <p>Used by the recovery sweep (§21 step 8). Rebuilding from the payload rather than storing a
     * serialized command is what keeps the job record queryable and log-safe — and the worker's
     * idempotent claim means a re-send of work that actually completed is harmless.
     *
     * <p>Package-private: only the sweep should re-dispatch. Application code creates jobs through
     * the {@link JobDispatcher} methods, which enforce the idempotency check first.
     */
    void redispatch(ProcessingJob job) {
        var payload = job.getPayload();
        var jobType = JobType.valueOf(job.getJobType());

        switch (jobType) {
            case DOCUMENT_PROCESSING -> send(job, j -> documentProcessingClient.post()
                    .uri("/internal/v1/document-processing/jobs")
                    .body(new DocumentProcessingCommand(
                            envelopeFor(j),
                            payload.get("documentVersionId"),
                            DocumentType.valueOf(payload.get("documentType")),
                            payload.get("sourceBucket"),
                            payload.get("sourceObjectKey"),
                            // Media type is advisory and the worker re-verifies the signature
                            // anyway (§8.2), so it is not worth a payload field of its own.
                            null,
                            callbackUrl("document-processing")))
                    .retrieve()
                    .body(JobAcceptedResponse.class));

            case STANDARD_INDEXING -> send(job, j -> retrievalComparisonClient.post()
                    .uri("/internal/v1/standard-indexing/jobs")
                    .body(new StandardIndexingCommand(
                            envelopeFor(j),
                            payload.get("standardId"),
                            payload.get("standardVersionId"),
                            // Title is prompt context only (§12.4), never authoritative evidence, so
                            // a re-dispatch that lost it still extracts correctly.
                            payload.getOrDefault("standardTitle", ""),
                            // Profile metadata on a RE-DISPATCH. Persisted on the job payload when it
                            // was first created, so a recovery sweep rebuilds the same profile vector
                            // rather than a weaker title-only one — a re-dispatched standard must not
                            // become less findable than it was.
                            payload.get("standardDescription"),
                            payload.get("standardCategory"),
                            payload.get("standardBusinessUnit"),
                            payload.get("standardFamily"),
                            payload.get("standardDocumentType"),
                            splitTags(payload.get("standardTags")),
                            payload.get("chunkingStrategyVersion"),
                            callbackUrl("standard-indexing")))
                    .retrieve()
                    .body(JobAcceptedResponse.class));

            case RETRIEVAL_COMPARISON -> send(job, j -> retrievalComparisonClient.post()
                    .uri("/internal/v1/comparison/jobs")
                    .body(new RetrievalComparisonCommand(
                            envelopeFor(j),
                            payload.get("comparisonId"),
                            payload.get("documentVersionId"),
                            splitIds(payload.get("standardVersionIds")),
                            DEFAULT_MAX_AUTOMATIC_STANDARDS,
                            // Absent for comparisons created before guidance existed, and for those
                            // started without it — null is the correct value in both cases.
                            payload.get("comparisonGuidance"),
                            callbackUrl("comparison")))
                    .retrieve()
                    .body(JobAcceptedResponse.class));
        }
    }

    /**
     * Splits the persisted, comma-joined standard version IDs.
     *
     * <p>Filters blanks so an empty stored value yields an empty list rather than a list holding one
     * empty string — which would look like an explicit standard selection of "" and suppress
     * automatic selection (§15.1).
     */
    private static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * The persist-before-dispatch sequence shared by all three job types.
     *
     * @param subject the immutable thing being worked on; forms the idempotency key
     */
    private String dispatch(JobType jobType, String subject, Map<String, String> payload,
                            WorkerCall call) {
        String idempotencyKey = jobType.name() + ":" + subject;

        // (1) Existing work wins. Returning the prior job makes a repeated dispatch a no-op rather
        // than a second set of artifacts (§21).
        var existing = jobs.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var job = existing.get();
            log.info("Job {} already exists for {} in state {}; not re-creating",
                    job.getId(), idempotencyKey, job.getState());
            return job.getId();
        }

        // (2) Durable BEFORE the network call. See the class comment for why this order.
        var job = jobs.save(new ProcessingJob(
                jobType.name(), idempotencyKey, payload,
                JobEnvelope.CURRENT_PAYLOAD_VERSION, properties.maxAttempts()));

        send(job, call);
        return job.getId();
    }

    /**
     * Posts to the worker and records the outcome on the job.
     *
     * <p>Also used by the recovery sweep to re-send an existing job, which is why it takes a job
     * rather than creating one.
     */
    void send(ProcessingJob job, WorkerCall call) {
        job.setAttempt(job.getAttempt() + 1);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);

        try {
            var accepted = call.post(job);

            job.setState(JobState.DISPATCHED);
            job.setDispatchedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            job.setFailureReason(null);
            jobs.save(job);

            if (accepted != null && accepted.duplicate()) {
                log.info("Worker reported job {} ({}) already claimed; no new work started",
                        job.getId(), job.getJobType());
            } else {
                log.info("Dispatched job {} ({}) attempt {}",
                        job.getId(), job.getJobType(), job.getAttempt());
            }
        } catch (Exception e) {
            // The job stays durable and non-terminal so the sweep retries it. Only a job that has
            // exhausted its attempts becomes FAILED, so a worker restart does not lose work.
            boolean retryable = job.retryable();
            job.setState(retryable ? JobState.PENDING : JobState.FAILED);
            job.setUpdatedAt(Instant.now());
            // Message only, never the exception: it can carry request bodies and headers (§10.5).
            job.setFailureReason(retryable
                    ? "Dispatch attempt " + job.getAttempt() + " failed; awaiting retry."
                    : "Dispatch failed after " + job.getAttempt() + " attempts.");
            if (!retryable) {
                job.setCompletedAt(Instant.now());
            }
            jobs.save(job);

            log.warn("Dispatch of job {} ({}) failed on attempt {} of {}: {}",
                    job.getId(), job.getJobType(), job.getAttempt(), job.getMaxAttempts(),
                    e.getClass().getSimpleName());
        }
    }

    private JobEnvelope envelopeFor(ProcessingJob job) {
        return new JobEnvelope(job.getId(), JobType.valueOf(job.getJobType()), job.getAttempt(),
                job.getIdempotencyKey(), job.getCreatedAt(), job.getPayloadVersion());
    }

    private String callbackUrl(String suffix) {
        String base = properties.callbackBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/internal/v1/callbacks/" + suffix;
    }

    /** Omits blanks rather than storing "null" strings, which would reach the prompt as text. */
    private static void putIfPresent(java.util.Map<String, String> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    /** Inverse of the comma join above. Absent or blank yields an empty list, never null. */
    private static List<String> splitTags(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
