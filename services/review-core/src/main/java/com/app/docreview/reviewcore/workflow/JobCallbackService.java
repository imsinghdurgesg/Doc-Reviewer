package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.job.ComparisonState;
import com.app.docreview.reviewcore.standards.application.CuratedMetadata;
import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.standards.repository.StandardRepository;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJob;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Applies worker completion callbacks. Architecture §21 steps 6–7, §39 rule 23.
 *
 * <p><strong>Idempotent by design.</strong> A worker that does not observe its callback succeed sends
 * it again, so this must be safe to run twice. Two guards do that work:
 *
 * <ol>
 *   <li>A callback for a job already in a terminal state is IGNORED. Re-applying it would move a
 *       finished job's timestamps and could overwrite a result with a stale duplicate.</li>
 *   <li>The idempotency key is verified against the stored job. A callback whose key does not match
 *       is rejected rather than applied — it means the worker is reporting on different work than
 *       the job record describes, and guessing which is right would corrupt both.</li>
 * </ol>
 *
 * <p>Standard-indexing callbacks also advance the {@code StandardVersion}, because §8.1 gates
 * activation on {@code INDEXED}. Without this the version stays UPLOADED forever and can never be
 * activated — the pipeline would complete and appear to have done nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobCallbackService {

    private final ProcessingJobRepository jobs;
    private final StandardVersionRepository versions;
    private final UploadedDocumentRepository documents;
    private final ComparisonRepository comparisons;
    private final ComparisonFindingReader findingReader;
    private final StandardRepository standards;
    private final JobDispatcher jobDispatcher;

    /** The result of applying a callback, for the controller's response. */
    public enum Outcome {
        APPLIED,
        /** Already terminal; the callback was a duplicate and changed nothing. */
        IGNORED_DUPLICATE,
        UNKNOWN_JOB,
        KEY_MISMATCH
    }

    public Outcome apply(JobCallback callback) {
        var maybeJob = jobs.findById(callback.jobId());
        if (maybeJob.isEmpty()) {
            // Not necessarily an attack or a bug: a job purged by retention could be reported late.
            // Logged and rejected rather than resurrected, because a job record is the only thing
            // that says what the work was for.
            log.warn("Callback for unknown job {}", callback.jobId());
            return Outcome.UNKNOWN_JOB;
        }

        var job = maybeJob.get();

        if (!job.getIdempotencyKey().equals(callback.idempotencyKey())) {
            log.error("Callback for job {} carries key {} but the job holds {}; rejecting",
                    job.getId(), callback.idempotencyKey(), job.getIdempotencyKey());
            return Outcome.KEY_MISMATCH;
        }

        if (terminal(job.getState())) {
            log.info("Callback for job {} ignored; already {}", job.getId(), job.getState());
            return Outcome.IGNORED_DUPLICATE;
        }

        job.setState(callback.terminalState());
        job.setProgressState(callback.progressState());
        job.setCompletedUnits(callback.completedUnits());
        job.setTotalUnits(callback.totalUnits());
        job.setFailureReason(callback.failureReason());
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobs.save(job);

        switch (callback.jobType()) {
            case STANDARD_INDEXING -> applyIndexingResult(job, callback);
            case DOCUMENT_PROCESSING -> applyDocumentProcessingResult(job, callback);
            case RETRIEVAL_COMPARISON -> applyComparisonResult(job, callback);
        }

        log.info("Applied callback for job {} ({}): {}{}",
                job.getId(), callback.jobType(), callback.terminalState(),
                callback.unitErrors().isEmpty()
                        ? "" : " with " + callback.unitErrors().size() + " unit error(s)");

        return Outcome.APPLIED;
    }

    /**
     * Advances the standard version's indexing state.
     *
     * <p>The mapping is deliberately conservative: only a fully COMPLETED indexing run reaches
     * {@code INDEXED}. A PARTIALLY_COMPLETED run means some requirements failed validation, which
     * §12.8 says must land in {@code NEEDS_REVIEW} rather than publish unverified controls — and
     * §8.1 then refuses activation, which is the outcome we want.
     */
    private void applyIndexingResult(ProcessingJob job, JobCallback callback) {
        String standardVersionId = job.getPayload().get("standardVersionId");
        if (standardVersionId == null) {
            log.warn("Indexing callback for job {} has no standardVersionId in its payload",
                    job.getId());
            return;
        }

        versions.findById(standardVersionId).ifPresentOrElse(version -> {
            var state = switch (callback.terminalState()) {
                case COMPLETED -> StandardIndexingState.INDEXED;
                case PARTIALLY_COMPLETED -> StandardIndexingState.NEEDS_REVIEW;
                default -> StandardIndexingState.FAILED;
            };

            version.setIndexingState(state);
            version.setIndexingMessage(callback.failureReason());

            var results = callback.results();
            applyIfPresent(results.get(JobCallback.RESULT_REQUIREMENT_SET_VERSION),
                    version::setRequirementSetVersion);
            applyIntIfPresent(results.get(JobCallback.RESULT_REQUIREMENT_COUNT),
                    version::setRequirementCount);
            applyIntIfPresent(results.get(JobCallback.RESULT_MATERIAL_REQUIREMENT_COUNT),
                    version::setMaterialRequirementCount);
            applyIfPresent(results.get(JobCallback.RESULT_EMBEDDING_MODEL_ALIAS),
                    version::setEmbeddingModelAlias);
            applyIntIfPresent(results.get(JobCallback.RESULT_EMBEDDING_DIMENSIONS),
                    version::setEmbeddingDimensions);

            // Structural issues, so the administrator can fix the source document rather than
            // guessing what "needs review" meant (§12.8).
            version.setExtractionIssues(callback.unitErrors());
            applyIntIfPresent(results.get(JobCallback.RESULT_CANDIDATE_COUNT),
                    version::setCandidateCount);
            applyIntIfPresent(results.get(JobCallback.RESULT_REJECTED_COUNT),
                    version::setRejectedRequirementCount);

            if (state == StandardIndexingState.INDEXED) {
                version.setIndexedAt(Instant.now());
            }

            versions.save(version);
            applyDerivedMetadata(version.getStandardId(), results);
            log.info("Standard version {} indexing state -> {}", standardVersionId, state);

        }, () -> log.warn("Indexing callback names unknown standard version {}", standardVersionId));
    }

    /**
     * Records parsing results on the uploaded document.
     *
     * <p>{@code spanCount} is what gates comparison creation ({@code UploadedDocument
     * .readyForComparison()}): a document with zero spans cannot be compared, because retrieval
     * would find nothing and every requirement would come back MISSING (§17.5).
     */
    private void applyDocumentProcessingResult(ProcessingJob job, JobCallback callback) {
        String documentVersionId = job.getPayload().get("documentVersionId");
        if (documentVersionId == null) {
            log.warn("Document-processing callback for job {} has no documentVersionId", job.getId());
            return;
        }

        // A STANDARD's processing also arrives here. Standards are tracked on StandardVersion, not
        // UploadedDocument, so an absent uploaded document is expected rather than an error.
        documents.findByDocumentVersionId(documentVersionId).ifPresentOrElse(document -> {
            var results = callback.results();

            var state = switch (callback.terminalState()) {
                case COMPLETED -> ComparisonState.GENERATING_CHUNKS;
                case PARTIALLY_COMPLETED -> ComparisonState.GENERATING_CHUNKS;
                default -> ComparisonState.FAILED;
            };
            document.setState(state);
            document.setStateMessage(callback.failureReason());

            applyIntIfPresent(results.get(JobCallback.RESULT_SPAN_COUNT), document::setSpanCount);
            applyIntIfPresent(results.get(JobCallback.RESULT_CHUNK_COUNT), document::setChunkCount);
            applyIntIfPresent(results.get(JobCallback.RESULT_PAGE_COUNT), document::setPageCount);
            applyIfPresent(results.get(JobCallback.RESULT_CHUNKING_STRATEGY_VERSION),
                    document::setChunkingStrategyVersion);
            // Present only when LibreOffice produced a preview (§8.2). Blank means none, and the
            // preview endpoint then falls back to serving the original source where it can.
            applyIfPresent(results.get(JobCallback.RESULT_RENDERED_OBJECT_KEY),
                    document::setRenderedObjectKey);
            document.setProcessedAt(Instant.now());

            documents.save(document);
            log.info("Document {} processed: {} span(s), {} chunk(s), ready={}",
                    documentVersionId, document.getSpanCount(), document.getChunkCount(),
                    document.readyForComparison());

        }, () -> chainStandardIndexing(documentVersionId, callback));
    }

    /**
     * Second stage of §20.1: parse succeeded, so extraction can now run.
     *
     * <p>Chained here rather than dispatched alongside the parse job, because requirement extraction
     * reads canonical spans (§12.1) and those do not exist until parsing completes. Dispatching both
     * up front would make extraction race the parser and find nothing — which §12.8 would then record
     * as a validated-but-empty requirement set, and §8.1 would happily activate a standard with no
     * controls.
     *
     * <p>Reached when a document-processing callback names no uploaded document, which is how a
     * STANDARD's parse is distinguished from a process document's.
     */
    private void chainStandardIndexing(String documentVersionId, JobCallback callback) {
        versions.findById(documentVersionId).ifPresentOrElse(version -> {
            var spanCount = callback.results().get(JobCallback.RESULT_SPAN_COUNT);
            var chunking = callback.results().getOrDefault(
                    JobCallback.RESULT_CHUNKING_STRATEGY_VERSION, "SEMANTIC-V1");

            applyIntIfPresent(spanCount, version::setSpanCount);
            applyIntIfPresent(callback.results().get(JobCallback.RESULT_CHUNK_COUNT),
                    version::setChunkCount);
            applyIfPresent(callback.results().get(JobCallback.RESULT_RENDERED_OBJECT_KEY),
                    version::setRenderedObjectKey);
            applyIntIfPresent(callback.results().get(JobCallback.RESULT_PAGE_COUNT), pages -> { });

            if (callback.terminalState() == JobState.FAILED) {
                version.setIndexingState(StandardIndexingState.FAILED);
                version.setIndexingMessage(callback.failureReason());
                versions.save(version);
                return;
            }

            // No spans means nothing to extract from. Failing here is deliberate: publishing an empty
            // requirement set would let §8.1 activate a standard against which every comparison
            // returns no findings — indistinguishable from full compliance.
            if ("0".equals(spanCount)) {
                version.setIndexingState(StandardIndexingState.FAILED);
                version.setIndexingMessage(
                        "No text was extracted from this standard. If it is a scanned PDF, its pages "
                                + "are images and no requirements can be extracted.");
                versions.save(version);
                log.warn("Standard version {} parsed to zero spans; not indexing", documentVersionId);
                return;
            }

            version.setIndexingState(StandardIndexingState.DETECTING_REQUIREMENT_CANDIDATES);
            versions.save(version);

            var standard = standards.findById(version.getStandardId()).orElse(null);
            // The profile drives WHICH standard a future upload is matched to (§15.1), so it is read
            // from the standard rather than left null — the worker cannot look it up itself, because
            // §22 keeps this collection private to review-core.
            var profile = standard == null
                    ? JobDispatcher.StandardProfile.empty()
                    : new JobDispatcher.StandardProfile(
                            standard.getDescription(), standard.getCategory(),
                            standard.getBusinessUnit(), standard.getStandardFamily(),
                            standard.getDocumentType(), standard.getTags());
            jobDispatcher.dispatchStandardIndexing(
                    version.getStandardId(), documentVersionId,
                    standard == null ? version.getVersion() : standard.getTitle(),
                    chunking, profile);

            log.info("Standard version {} parsed ({} spans); dispatched requirement extraction",
                    documentVersionId, spanCount);

        }, () -> log.warn("Document-processing callback names neither an uploaded document nor a "
                + "standard version: {}", documentVersionId));
    }

    /**
     * Records comparison results and the §19.2 reproducibility snapshot.
     *
     * <p>Findings are FETCHED from the worker rather than carried in the callback body. §21 keeps job
     * payloads to references and counts, and a comparison's findings can run to hundreds of entries
     * with reason text — too large for a callback that is retried, logged, and must stay cheap.
     */
    private void applyComparisonResult(ProcessingJob job, JobCallback callback) {
        String comparisonId = job.getPayload().get("comparisonId");
        if (comparisonId == null) {
            log.warn("Comparison callback for job {} has no comparisonId", job.getId());
            return;
        }

        comparisons.findByPublicId(comparisonId).ifPresentOrElse(comparison -> {
            var state = switch (callback.terminalState()) {
                case COMPLETED -> ComparisonState.COMPLETED;
                case PARTIALLY_COMPLETED -> ComparisonState.PARTIALLY_COMPLETED;
                default -> ComparisonState.FAILED;
            };

            comparison.setState(state);
            comparison.setStateMessage(callback.failureReason());
            comparison.setEvaluatedRequirements(callback.completedUnits());
            comparison.setTotalRequirements(callback.totalUnits());
            // Which controls failed, not just how many. Previously these arrived and were dropped,
            // leaving "1 of 15 could not be evaluated" with nothing behind it (§18.4).
            comparison.setUnevaluatedRequirements(callback.unitErrors());
            comparison.setCompletedAt(Instant.now());

            // §19.2 / §39 rule 30: store every version that produced the result, so reopening it
            // resolves what was actually measured rather than what is current.
            var results = callback.results();
            applyIfPresent(results.get(JobCallback.RESULT_REQUIREMENT_SET_VERSION),
                    comparison::setRequirementSetVersion);
            applyIfPresent(results.get(JobCallback.RESULT_EMBEDDING_MODEL_ALIAS),
                    comparison::setEmbeddingModelAlias);
            applyIntIfPresent(results.get(JobCallback.RESULT_EMBEDDING_DIMENSIONS),
                    comparison::setEmbeddingDimensions);
            applyIfPresent(results.get(JobCallback.RESULT_COMPARISON_MODEL_ALIAS),
                    comparison::setComparisonModelAlias);
            applyIfPresent(results.get(JobCallback.RESULT_COMPARISON_PROMPT_VERSION),
                    comparison::setComparisonPromptVersion);
            applyIfPresent(results.get(JobCallback.RESULT_COMPARISON_GUIDANCE),
                    comparison::setComparisonGuidance);
            applyIfPresent(results.get(JobCallback.RESULT_COMPARISON_SCHEMA_VERSION),
                    comparison::setComparisonSchemaVersion);
            applyIfPresent(results.get(JobCallback.RESULT_RETRIEVAL_STRATEGY_VERSION),
                    comparison::setRetrievalStrategyVersion);
            applyIfPresent(results.get(JobCallback.RESULT_INDEX_VERSION),
                    comparison::setIndexVersion);

            if (state != ComparisonState.FAILED) {
                var findings = findingReader.read(comparisonId);
                comparison.setFindings(findings);

                // THE COVERAGE SNAPSHOT, and the invariant that goes with it (§17.6, §19.2).
                //
                // Copied onto the authoritative comparison so it becomes immutable with the review:
                // the worker's collection is a working set a re-run replaces, and a completed review
                // must not have its coverage rewritten afterwards.
                //
                // A terminal comparison created under this schema REQUIRES a snapshot. Reaching
                // COMPLETED with no coverage record is an operational failure, not a legacy gap —
                // persisting it would produce a comparison that claims to be complete while being
                // unable to say what it covered, which is precisely the state this feature exists to
                // make impossible. So the comparison is failed instead, loudly.
                var fetch = findingReader.readCoverage(comparisonId);
                // A null fetch is itself a broken reader, not an answer — same reasoning as an empty
                // body. Never let it read as absence.
                if (fetch == null) {
                    fetch = ComparisonFindingReader.CoverageFetch.unavailable("null fetch result");
                }
                switch (fetch.outcome()) {
                    case FOUND -> comparison.setCoverageLedger(fetch.ledger());

                    case NOT_RECORDED -> {
                        // Only legitimate for a comparison that predates persistence. One created
                        // under this schema cannot legitimately lack a ledger, so treat it as the
                        // failure it is rather than silently degrading to "not recorded".
                        if (requiresCoverageSnapshot(comparison)) {
                            failForMissingCoverage(comparison, state,
                                    "the worker holds no coverage ledger");
                            return;
                        }
                        log.info("Comparison {} predates coverage persistence; no snapshot stored",
                                comparisonId);
                    }

                    case UNAVAILABLE -> {
                        // Coverage is UNKNOWN, not absent. Never store a terminal state we cannot
                        // substantiate: a retry can still succeed, and the sweep will re-deliver.
                        failForMissingCoverage(comparison, state,
                                "coverage could not be read (" + fetch.failure() + ")");
                        return;
                    }
                }

                // Backfill the standards actually used, for a comparison created with an empty
                // selection (§15.1). Without this the stored list stays empty and the workspace can
                // name no reference at all — the review is correct but appears to have measured
                // against nothing, and the history row shows a blank standard.
                //
                // Derived from the findings rather than a new callback field: each finding already
                // carries the standardVersionId it was evaluated against, so this is the same fact
                // read from where it is recorded rather than transmitted twice.
                if (comparison.getStandardVersionIds() == null
                        || comparison.getStandardVersionIds().isEmpty()) {
                    var used = findings.stream()
                            .map(com.app.docreview.contracts.comparison.Finding::standardVersionId)
                            .filter(id -> id != null && !id.isBlank())
                            .distinct()
                            .toList();
                    if (!used.isEmpty()) {
                        comparison.setStandardVersionIds(used);
                        log.info("Comparison {} used {} auto-selected standard(s)",
                                comparisonId, used.size());
                    }
                }
            }

            comparisons.save(comparison);
            log.info("Comparison {} -> {} with {} finding(s), {}/{} requirements evaluated",
                    comparisonId, state, comparison.getFindings().size(),
                    callback.completedUnits(), callback.totalUnits());

        }, () -> log.warn("Comparison callback names unknown comparison {}", comparisonId));
    }

    /**
     * Whether this comparison must carry a coverage snapshot to reach a terminal state. §17.6.
     *
     * <p>Keyed on {@code coverageSchemaVersion}, stamped at creation. A comparison created before
     * coverage persistence has none and is exempt — the 48 existing reviews are legitimately
     * "not recorded" and must not be rerun or backfilled to satisfy a rule written after them.
     *
     * <p>Everything created since is required to have one. Deriving the distinction from a stamped
     * marker rather than from a date or from the ledger's own presence matters: presence cannot
     * distinguish "exempt" from "broken", which is the whole point of the invariant.
     */
    private boolean requiresCoverageSnapshot(Comparison comparison) {
        return comparison.getCoverageSchemaVersion() != null;
    }

    /**
     * Refuses to record a terminal state that has no coverage to substantiate it.
     *
     * <p>FAILED with an explicit message rather than COMPLETED-without-coverage. A comparison that
     * cannot say what it covered is not a completed review, and presenting one as complete is the
     * failure mode §17.6 exists to prevent — a reviewer would act on a conclusion whose scope nobody
     * can establish.
     *
     * <p>Deliberately does NOT clear findings: they are real, the reviewer may want them, and
     * discarding evidence because its coverage record is missing destroys more than it protects.
     */
    private void failForMissingCoverage(Comparison comparison, ComparisonState attempted,
                                        String why) {
        comparison.setState(ComparisonState.FAILED);
        comparison.setStateMessage(
                "Coverage snapshot missing, so " + attempted.name().toLowerCase().replace('_', ' ')
                        + " cannot be recorded: " + why
                        + ". Rerun the comparison; its findings are retained but its coverage is "
                        + "unverified.");
        comparison.setCompletedAt(Instant.now());
        comparisons.save(comparison);
        log.error("Comparison {} reached {} without a coverage snapshot ({}); recorded FAILED. "
                        + "A terminal comparison must be able to state what it covered (§17.6).",
                comparison.getPublicId(), attempted, why);
    }

    private void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    /**
     * Parses a count and applies it, ignoring malformed values.
     *
     * <p>A worker sending a non-numeric count is a bug worth logging, but it must not fail the whole
     * callback: the job's terminal state is far more important than one diagnostic counter.
     */
    private void applyIntIfPresent(String value, java.util.function.Consumer<Integer> setter) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            setter.accept(Integer.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("Ignoring non-numeric count '{}' in callback results", value);
        }
    }

    private boolean terminal(JobState state) {
        return state == JobState.COMPLETED
                || state == JobState.PARTIALLY_COMPLETED
                || state == JobState.FAILED
                || state == JobState.CANCELLED;
    }

    /**
     * Stores metadata the worker derived from a standard's own text. Architecture §15.1.
     *
     * <p>It already reached the selection vector, but a value living only inside an embedding cannot be
     * read, checked, or corrected — and an inferred category nobody can see is one nobody can tell is
     * wrong. Persisting it also lets a later re-index skip the model call.
     *
     * <p><strong>Never overwrites a value that is already set.</strong> A human who filled in the form
     * knows more than a model reading the first few pages, and a re-index must not quietly replace
     * their entry. The one exception is the bucket-sync placeholder description, which is the same
     * string on every synced standard and is precisely what this replaces.
     */
    private void applyDerivedMetadata(String standardId, Map<String, String> results) {
        if (standardId == null) {
            return;
        }
        standards.findById(standardId).ifPresent(standard -> {
            boolean changed = false;

            String description = results.get(JobCallback.RESULT_DERIVED_DESCRIPTION);
            if (CuratedMetadata.isMeaningful(description)
                    && isReplaceable(standard.getDescription())) {
                standard.setDescription(description.trim());
                changed = true;
            }

            // Curated values win, and a placeholder never lands. `category` previously only checked
            // null-or-blank, so a model answering "Unknown" would be stored and thereafter look
            // exactly like an administrator's choice — permanently blocking the real value.
            String category = results.get(JobCallback.RESULT_DERIVED_CATEGORY);
            if (CuratedMetadata.wouldChange(standard.getCategory(), category)) {
                standard.setCategory(CuratedMetadata.preserve(standard.getCategory(), category));
                changed = true;
            }

            String tags = results.get(JobCallback.RESULT_DERIVED_TAGS);
            if (tags != null && (standard.getTags() == null || standard.getTags().isEmpty())) {
                standard.setTags(java.util.Arrays.stream(tags.split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList());
                changed = true;
            }

            if (changed) {
                standards.save(standard);
                // Counts only: a description is standard content (§35).
                log.info("Stored derived metadata for standard {}", standard.getStandardKey());
            }
        });
    }

    /** True when a description carries no human intent — absent, or the bucket-sync placeholder. */
    private static boolean isReplaceable(String description) {
        return description == null || description.isBlank()
                || "Synced from the policy bucket.".equals(description.trim());
    }
}
