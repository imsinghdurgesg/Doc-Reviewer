package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.retrievalcomparison.extraction.RequirementExtractionService;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import com.app.docreview.retrievalcomparison.indexing.VectorIndexingService;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one standard-indexing job. Architecture §20.1, §12.
 *
 * <pre>
 *   load spans -> detect candidates -> extract -> validate -> persist set -> embed -> index
 * </pre>
 *
 * <p>Extraction runs ONCE per immutable standard version and is reused by every later comparison
 * (§12.1, §39 rule 19). The idempotent claim upstream is what enforces that; this runner assumes it
 * has exclusive ownership of the version it was handed.
 *
 * <p>A {@code NEEDS_REVIEW} extraction is reported as {@link JobState#PARTIALLY_COMPLETED}, not
 * FAILED. §12.8 requires validation failures to withhold publication without discarding the
 * requirements that did validate — and §8.1 refuses to activate a version that is not INDEXED, so a
 * partial set cannot silently become an active standard.
 *
 * <p><strong>Never throws to the caller.</strong> It runs on the executor after the 202 was sent, so
 * an escaped exception would kill the worker thread and leave the job non-terminal forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardIndexingJobRunner {

    /**
     * Passages sent to the profiling model. Matches StandardSelectionService's own limit, so both
     * sides of the match are characterised from a comparable amount of text.
     */
    private static final int PROFILE_SPAN_LIMIT = 40;

    /** What StandardsBucketSync writes when it creates a standard from a bucket path. */
    private static final String SYNC_PLACEHOLDER_DESCRIPTION = "Synced from the policy bucket.";

    private final EvidenceSpanReader spanReader;
    private final RequirementExtractionService extractionService;
    private final VectorIndexingService indexingService;
    private final StandardRequirementRepository requirementRepository;
    private final WorkerJobRepository jobs;
    private final JobCallbackSender callbackSender;
    /** Reads metadata out of a standard when the administered fields are absent (§15.1). */
    private final com.app.docreview.retrievalcomparison.llm.StandardProfileModelGateway profileGateway;

    public void run(WorkerJob job) {
        if (job.terminal()) {
            // The sweep re-dispatched work that in fact finished. Resend the callback rather than
            // re-running an extraction that would cost budget and publish a second set (§12.9).
            log.info("Job {} already terminal ({}); resending callback only",
                    job.getId(), job.getState());
            sendCallback(job, job.getState(), job.getFailureReason(), Map.of());
            return;
        }

        try {
            advance(job, "DETECTING_REQUIREMENT_CANDIDATES");

            // Spans are owned by document-processing and read through the port, never by querying
            // its database (§22, §9.8). Candidate detection scans the whole document (§12.2), so
            // this is allForVersion rather than a byIds fetch.
            var spans = spanReader.allForVersion(job.getStandardVersionId());
            if (spans.isEmpty()) {
                // Not a crash: a standard whose parse produced nothing cannot be indexed, and
                // publishing an empty requirement set would make every comparison against it read
                // as full compliance (§8.1).
                fail(job, "No evidence spans exist for this standard version. "
                        + "Document processing must complete before indexing.");
                return;
            }

            advance(job, "EXTRACTING_REQUIREMENTS");
            var run = extractionService.extract(
                    job.getStandardId(), job.getStandardVersionId(), job.getStandardTitle(),
                    spans, job.getChunkingStrategyVersion());

            advance(job, "GENERATING_EMBEDDINGS");
            var requirements = requirementRepository
                    .findByStandardVersionIdAndRequirementSetVersion(
                            job.getStandardVersionId(), run.getRequirementSetVersion());

            // Descriptive metadata for the selection vector (§15.1).
            //
            // These were hardcoded null, so every profile was embedded from the title alone — two
            // versions of "Technology Change Management Standard" produced nearly identical vectors
            // with nothing to separate them.
            //
            // Administered values WIN over derived ones: a human who filled in the form knows more
            // than a model reading the first few pages. Derivation fills the gap for standards synced
            // from the bucket, which have no form and previously shared one identical hardcoded
            // description — text common to every standard pulls their vectors together rather than
            // apart, which is worse than leaving it blank.
            //
            // Owner is omitted deliberately: it names a person, which says nothing about subject
            // matter, and would make two unrelated standards match because one team owns both.
            var derived = deriveProfileIfNeeded(job, spans);
            // One metadata set, inherited by the profile vector AND every requirement chunk, so
            // document-level and control-level retrieval rank on the same signals (§15.1).
            // standardKey, version, and lifecycle are NOT available on the indexing command, so they
            // are left null here and populated by the projection path instead
            // (VectorProjectionClient -> /reembed), which reads them from Mongo.
            //
            // Deliberate rather than an oversight: those three describe the standard RECORD, which
            // review-core owns, and lifecycle in particular changes after indexing — a version is
            // indexed while DRAFT and activated afterwards, so a value captured here would be stale
            // exactly when it matters. Candidate grouping treats a missing standardKey as "its own
            // group, always eligible", so a not-yet-projected version stays selectable.
            var metadata = com.app.docreview.retrievalcomparison.indexing.VectorIndexingService
                    .StandardMetadata.of(
                    job.getStandardBusinessUnit(),
                    firstPresent(job.getStandardCategory(), derived.category()),
                    job.getStandardFamily(),
                    job.getStandardDocumentType(),
                    job.getStandardTags() == null || job.getStandardTags().isEmpty()
                            ? derived.tags() : job.getStandardTags());
            indexingService.indexStandardProfile(
                    job.getStandardId(), job.getStandardVersionId(), job.getStandardTitle(),
                    firstPresent(job.getStandardDescription(), derived.description()),
                    null,
                    metadata);

            advance(job, "INDEXING_STANDARD");
            indexingService.indexRequirements(requirements, metadata);

            boolean needsReview = run.getValidationStatus()
                    == StandardRequirementDocument.ValidationStatus.NEEDS_REVIEW;
            var terminal = needsReview ? JobState.PARTIALLY_COMPLETED : JobState.COMPLETED;

            job.setState(terminal);
            job.setProgressState(needsReview ? "NEEDS_REVIEW" : "INDEXED");
            job.setCompletedUnits(run.getRequirementCount());
            job.setTotalUnits(run.getCandidateCount());
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobs.save(job);

            var results = new HashMap<String, String>();
            results.put(JobCallback.RESULT_REQUIREMENT_SET_VERSION, run.getRequirementSetVersion());
            results.put(JobCallback.RESULT_REQUIREMENT_COUNT,
                    String.valueOf(run.getRequirementCount()));
            results.put(JobCallback.RESULT_MATERIAL_REQUIREMENT_COUNT,
                    String.valueOf(run.getMaterialRequirementCount()));
            results.put(JobCallback.RESULT_CHUNKING_STRATEGY_VERSION,
                    job.getChunkingStrategyVersion());
            // Derived metadata, reported so review-core can store it on the standard. Only what the
            // model actually produced — an absent key leaves the stored value untouched, so a human's
            // entry is never overwritten by a later re-index.
            if (derived.available()) {
                if (derived.description() != null) {
                    results.put(JobCallback.RESULT_DERIVED_DESCRIPTION, derived.description());
                }
                if (derived.category() != null) {
                    results.put(JobCallback.RESULT_DERIVED_CATEGORY, derived.category());
                }
                if (derived.tags() != null && !derived.tags().isEmpty()) {
                    results.put(JobCallback.RESULT_DERIVED_TAGS, String.join(",", derived.tags()));
                }
            }

            results.put(JobCallback.RESULT_CANDIDATE_COUNT, String.valueOf(run.getCandidateCount()));
            results.put(JobCallback.RESULT_REJECTED_COUNT, String.valueOf(run.getRejectedCount()));
            var compatibility = indexingService.compatibility(job.getChunkingStrategyVersion());
            results.put(JobCallback.RESULT_EMBEDDING_MODEL_ALIAS,
                    String.valueOf(compatibility.embeddingModelAlias()));
            results.put(JobCallback.RESULT_EMBEDDING_DIMENSIONS,
                    String.valueOf(compatibility.embeddingDimensions()));

            log.info("Indexed standard version {} as requirement set {} ({} requirements, status {})",
                    job.getStandardVersionId(), run.getRequirementSetVersion(),
                    run.getRequirementCount(), run.getValidationStatus());

            // The per-item validation errors travel as unitErrors — the field §18.4 already defines
            // for "failures that did not fail the job". Without them the administrator sees only
            // "needs review" and has no way to know WHICH clause in their document to fix.
            var unitErrors = run.getValidationErrors() == null
                    ? List.<String>of() : run.getValidationErrors();

            sendCallback(job, terminal, needsReview
                    ? "%d of %d extracted requirements failed validation. The set was not published."
                            .formatted(run.getRejectedCount(),
                                    run.getRequirementCount() + run.getRejectedCount())
                    : null, unitErrors, results);

        } catch (Exception e) {
            // Display-safe reason only: extraction exceptions can quote prompts and document text
            // (§10.5, §34).
            fail(job, "Standard indexing failed during "
                    + (job.getProgressState() == null ? "extraction" : job.getProgressState()) + ".");
            log.error("Indexing job {} failed for standard version {}: {}",
                    job.getId(), job.getStandardVersionId(), e.getClass().getSimpleName(), e);
        }
    }

    private void fail(WorkerJob job, String reason) {
        job.setState(JobState.FAILED);
        job.setProgressState("FAILED");
        job.setFailureReason(reason);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        sendCallback(job, JobState.FAILED, reason, Map.of());
    }

    private void advance(WorkerJob job, String progressState) {
        job.setProgressState(progressState);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        log.debug("Job {} -> {}", job.getId(), progressState);
    }

    private void sendCallback(WorkerJob job, JobState state, String failureReason,
                              Map<String, String> results) {
        sendCallback(job, state, failureReason, List.of(), results);
    }

    private void sendCallback(WorkerJob job, JobState state, String failureReason,
                              List<String> unitErrors, Map<String, String> results) {
        var callback = new JobCallback(
                job.getOriginJobId(), JobType.STANDARD_INDEXING, job.getIdempotencyKey(),
                state, job.getProgressState(), job.getCompletedUnits(), job.getTotalUnits(),
                failureReason,
                // Bounded: a badly structured standard can produce dozens, and the callback is
                // logged and retried (§21).
                unitErrors.stream().limit(20).toList(),
                results);

        if (callbackSender.send(job.getCallbackUrl(), callback)) {
            job.setCallbackDelivered(true);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        }
    }

    /**
     * Reads metadata out of the document when the administered fields are missing.
     *
     * <p>Skipped entirely when a human already supplied description, category, and tags — there is
     * nothing to gain and it would spend a model call per standard for no benefit.
     *
     * <p>The SUBSTITUTE for the bucket-sync path, which has no form to fill in. Also skipped when the
     * sync's placeholder description is the only value present, because that string is identical
     * across every synced standard and is precisely what needs replacing.
     */
    private com.app.docreview.retrievalcomparison.llm.StandardProfileModelGateway.Result deriveProfileIfNeeded(
            WorkerJob job, List<com.app.docreview.contracts.evidence.EvidenceSpan> spans) {

        boolean described = job.getStandardDescription() != null
                && !job.getStandardDescription().isBlank()
                && !SYNC_PLACEHOLDER_DESCRIPTION.equals(job.getStandardDescription().trim());
        boolean categorised = job.getStandardCategory() != null
                && !job.getStandardCategory().isBlank();
        boolean tagged = job.getStandardTags() != null && !job.getStandardTags().isEmpty();

        if (described && categorised && tagged) {
            return com.app.docreview.retrievalcomparison.llm.StandardProfileModelGateway.Result.unavailable();
        }

        // The OPENING of the standard: title, purpose, scope. Not the whole document — what is wanted
        // is what it is about, and forty pages of clauses dilute that toward the average standard.
        String opening = spans.stream()
                .limit(PROFILE_SPAN_LIMIT)
                .map(com.app.docreview.contracts.evidence.EvidenceSpan::exactText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));

        return profileGateway.describe(job.getStandardTitle(), opening);
    }

    /** The administered value, or the derived one when it is absent. */
    private static String firstPresent(String administered, String derived) {
        return administered != null && !administered.isBlank() ? administered : derived;
    }
}
