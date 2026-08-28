package com.app.docreview.contracts.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Dispatches requirement extraction and indexing for one standard version. Architecture §12, §20.1.
 *
 * <p>Runs AFTER document-processing has produced spans for the version — extraction operates on
 * canonical spans, not on raw bytes (§12.1). The dispatcher enforces that ordering; this command
 * names the version whose spans are ready.
 *
 * <p>Extraction happens ONCE per immutable standard version and is reused by every later comparison
 * (§12.1). That is why the idempotency key matters more here than anywhere else: a duplicate run
 * would publish a second requirement-set version, and comparisons would silently split across two
 * sets of controls.
 */
public record StandardIndexingCommand(

        @NotNull @Valid JobEnvelope envelope,

        @NotBlank String standardId,

        /** Immutable version being indexed. Never re-points at newer content (§19.2). */
        @NotBlank String standardVersionId,

        /** Used in the extraction prompt for context; not authoritative evidence (§12.4). */
        @NotBlank String standardTitle,

        /**
         * Descriptive metadata, carried so the worker can build the selection profile. Architecture
         * §15.1.
         *
         * <p>These travel WITH THE JOB rather than being read from the worker's own storage, because
         * §22 keeps review-core's standards collection private to review-core. Without them the worker
         * passed nulls to {@code indexStandardProfile} and every profile vector was embedded from the
         * title alone — so two versions of "Technology Change Management Standard" produced nearly
         * identical vectors with nothing to tell them apart.
         *
         * <p>All optional: a standard created before these were captured still indexes, just with a
         * weaker profile. Blank is a real state here, not an error.
         */
        String standardDescription,
        String standardCategory,
        String standardBusinessUnit,

        /**
         * Family and governed document type. Architecture §15.1.
         *
         * <p>Ranking signals for candidate selection, carried on the job because §22 keeps
         * review-core's standards collection private to review-core — the worker has no other way to
         * learn them, and a chunk indexed without them cannot be ranked by them later.
         */
        String standardFamily,
        String standardDocumentType,

        java.util.List<String> standardTags,

        /**
         * Chunking strategy that produced the spans, recorded on the requirement set (§12.9).
         * A comparison run under different chunking is not comparable, so this travels with the job
         * rather than being read from whatever the worker happens to be configured with now.
         */
        @NotBlank String chunkingStrategyVersion,

        @NotBlank String callbackUrl) {
}
