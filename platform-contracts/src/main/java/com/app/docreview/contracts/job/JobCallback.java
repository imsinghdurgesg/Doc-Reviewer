package com.app.docreview.contracts.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * A worker's completion report. Architecture §21 step 6, §20.3.
 *
 * <p>Must be idempotent at the receiver: a worker that does not observe its callback succeed will
 * send it again, and §21 requires the repeat to change nothing. review-core keys on {@code jobId}
 * and ignores a callback for a job already in a terminal state.
 *
 * <p>{@code terminalState} is restricted to COMPLETED, PARTIALLY_COMPLETED, or FAILED — the states
 * §21 defines as terminal. A worker reporting RUNNING is reporting progress, which belongs on the
 * progress fields, not here.
 *
 * <p>{@code results} carries IDs and counts only — a requirement-set version, a produced span count.
 * Never document text or model output: this payload is logged, and §10.5 forbids logging document
 * bodies. The real artifacts are in MinIO and the worker's own collections; review-core reads them
 * by reference.
 */
public record JobCallback(

        @NotBlank String jobId,

        @NotNull JobType jobType,

        /** Echoed so a late duplicate cannot be mistaken for a fresh result (§21). */
        @NotBlank String idempotencyKey,

        @NotNull JobState terminalState,

        /**
         * Fine-grained progress state reached, for display (§21). For indexing this is a
         * {@link StandardIndexingState}; for a comparison a {@link ComparisonState}. Kept as a
         * string because the two vocabularies differ per job type.
         */
        String progressState,

        /** Units finished and attempted. §18.4 requires partial success to be visible, not rounded. */
        Integer completedUnits,

        Integer totalUnits,

        /**
         * Display-safe failure reason. Never a stack trace or document content (§34) — this reaches
         * the browser through the job's status.
         */
        String failureReason,

        /**
         * Per-unit errors that did not fail the job. §18.4: a comparison where three of two hundred
         * evaluations failed keeps the other 197, and the failures must still be inspectable.
         */
        List<String> unitErrors,

        /** IDs, versions, and counts produced by the job. Never text. */
        Map<String, String> results) {

    public JobCallback {
        unitErrors = unitErrors == null ? List.of() : List.copyOf(unitErrors);
        results = results == null ? Map.of() : Map.copyOf(results);
    }

    /** Result key naming the requirement set an indexing job published (§12.9). */
    public static final String RESULT_REQUIREMENT_SET_VERSION = "requirementSetVersion";
    public static final String RESULT_REQUIREMENT_COUNT = "requirementCount";
    public static final String RESULT_MATERIAL_REQUIREMENT_COUNT = "materialRequirementCount";
    /** How many candidate groups the detector found, so "7 sections -> 9 requirements" is showable. */
    /**
     * Metadata the worker DERIVED from a standard's own text. Architecture §15.1.
     *
     * <p>Reported back so it can be stored on the standard and seen. It already reaches the selection
     * vector, but a value that exists only inside an embedding cannot be read, checked, or corrected —
     * and an inferred category that nobody can see is one nobody can tell is wrong.
     *
     * <p>Tags are comma-joined: the results map is flat strings, so a list has to be flattened.
     */
    public static final String RESULT_DERIVED_DESCRIPTION = "derivedDescription";
    public static final String RESULT_DERIVED_CATEGORY = "derivedCategory";
    public static final String RESULT_DERIVED_TAGS = "derivedTags";

    public static final String RESULT_CANDIDATE_COUNT = "candidateCount";
    /** Requirements rejected by validation (§12.8). Non-zero is why a set lands in NEEDS_REVIEW. */
    public static final String RESULT_REJECTED_COUNT = "rejectedCount";
    public static final String RESULT_EMBEDDING_MODEL_ALIAS = "embeddingModelAlias";
    public static final String RESULT_EMBEDDING_DIMENSIONS = "embeddingDimensions";
    public static final String RESULT_CHUNKING_STRATEGY_VERSION = "chunkingStrategyVersion";
    public static final String RESULT_SPAN_COUNT = "spanCount";
    public static final String RESULT_CHUNK_COUNT = "chunkCount";
    public static final String RESULT_PAGE_COUNT = "pageCount";
    /** MinIO key of a rendered PDF preview, when one was produced (§8.2). Blank when not. */
    public static final String RESULT_RENDERED_OBJECT_KEY = "renderedObjectKey";
    public static final String RESULT_FINDING_COUNT = "findingCount";

    // Coverage ledger (§17.6). Reported alongside the finding count because a finding count alone is
    // not interpretable: 3 findings from 30 evaluated requirements and 3 findings from 8 evaluated
    // out of 30 look identical, and only the first supports any inference about the other 27.
    public static final String RESULT_REQUIREMENTS_EXPECTED = "requirementsExpected";
    public static final String RESULT_REQUIREMENTS_EVALUATED = "requirementsEvaluated";
    /** Requirements whose absence from the findings must NOT be read as compliance. */
    public static final String RESULT_REQUIREMENTS_UNTESTED = "requirementsUntested";

    // §19.2 reproducibility snapshot. Reported by the worker because only it knows which alias,
    // prompt, and schema actually ran — review-core must not assume its own configuration matches.
    public static final String RESULT_COMPARISON_MODEL_ALIAS = "comparisonModelAlias";
    public static final String RESULT_COMPARISON_PROMPT_VERSION = "comparisonPromptVersion";

    /**
     * The administrator guidance in force for this comparison. Architecture §19.2.
     *
     * <p>Stored ALONGSIDE the version, not instead of it. The version's digest says that instructions
     * differed between two runs; only the text says how — and a finding is defensible when someone can
     * read what the model was told, not merely observe that it changed.
     *
     * <p>Absent when no guidance was configured, which is the common case and keeps the payload small.
     */
    public static final String RESULT_COMPARISON_GUIDANCE = "comparisonGuidance";
    public static final String RESULT_COMPARISON_SCHEMA_VERSION = "comparisonSchemaVersion";
    public static final String RESULT_RETRIEVAL_STRATEGY_VERSION = "retrievalStrategyVersion";
    public static final String RESULT_INDEX_VERSION = "indexVersion";
}
