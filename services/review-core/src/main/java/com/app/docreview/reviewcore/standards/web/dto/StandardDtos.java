package com.app.docreview.reviewcore.standards.web.dto;

import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request and response records for the standards API. Architecture §25.
 *
 * <p>Records rather than Lombok classes: these are serialization contracts, and immutability means a
 * controller cannot accidentally mutate a request after validation.
 */
public final class StandardDtos {

    private StandardDtos() {
    }

    /**
     * @param standardKey business identity, immutable once created. Constrained to an uppercase
     *                    token because it appears in requirement keys ({@code GRC-CHG:2.4:4:1}) and
     *                    a key containing a colon or space would make those ambiguous.
     */
    public record CreateStandardRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]{1,63}",
                    message = "must be uppercase alphanumeric with . _ - and no spaces")
            String standardKey,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 200) String owner,
            @Size(max = 200) String businessUnit,
            @Size(max = 120) String category,
            /** Groups standards habitually assessed together, e.g. THIRD_PARTY_RISK (§15.1). */
            @Size(max = 120) String standardFamily,
            /** The kind of document this standard governs, e.g. PROCUREMENT_PROCEDURE. */
            @Size(max = 120) String documentType,
            List<String> tags
    ) {}

    /**
     * Edits a standard's curated metadata. Architecture §15.1.
     *
     * <p>MongoDB is the source of truth for these three ranking dimensions; Qdrant payloads are a
     * projection rebuilt from here. An administrator editing a label is therefore the authoritative
     * act, and reprocessing must never overwrite it (see {@code CuratedMetadata}).
     *
     * <p><strong>Null means "leave unchanged", not "clear".</strong> A PATCH that treated absent fields
     * as clears would wipe curation whenever the UI sent a partial form. Clearing is done by sending an
     * empty string, which is explicit.
     */
    public record UpdateStandardMetadataRequest(
            @Size(max = 200) String businessUnit,
            @Size(max = 120) String category,
            @Size(max = 120) String standardFamily,
            @Size(max = 120) String documentType,
            List<String> tags
    ) {}

    public record StandardResponse(
            String id,
            String standardKey,
            String title,
            String description,
            String owner,
            String businessUnit,
            String category,
            String standardFamily,
            String documentType,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            List<VersionResponse> versions
    ) {}

    public record VersionResponse(
            String id,
            String standardId,
            String version,
            String contentSha256,
            String sourceFilename,
            String sourceMediaType,
            long sourceSizeBytes,
            LocalDate effectiveDate,
            LocalDate reviewDate,
            StandardLifecycle lifecycle,
            StandardIndexingState indexingState,
            String indexingMessage,

            /**
             * Structural problems found during extraction (§12.8), phrased for the person who owns
             * the document. Empty when the version indexed cleanly.
             *
             * <p>Surfaced because "needs review" alone gives an administrator nothing to act on: the
             * fix is almost always in the source document — duplicate clause numbers, a definition
             * written as an obligation — and only these lines say which.
             */
            java.util.List<String> extractionIssues,

            /** Candidate sections the detector found, for a "7 sections -> 9 requirements" summary. */
            int candidateCount,

            /** Requirements rejected by validation; non-zero explains a NEEDS_REVIEW version. */
            int rejectedRequirementCount,

            String requirementSetVersion,
            int requirementCount,
            int materialRequirementCount,
            String embeddingModelAlias,
            Integer embeddingDimensions,
            Instant createdAt,
            Instant indexedAt,
            /** True only when the version may be used for a NEW comparison (§8.1). */
            boolean comparable
    ) {}

    /**
     * Lifecycle transition request.
     *
     * <p>Only the lifecycle is settable. Indexing state is owned by the indexing pipeline — letting
     * an administrator set it directly would allow marking an unindexed version INDEXED, producing
     * comparisons with no requirements that read as full compliance.
     */
    public record UpdateLifecycleRequest(
            @jakarta.validation.constraints.NotNull StandardLifecycle lifecycle
    ) {}
}
