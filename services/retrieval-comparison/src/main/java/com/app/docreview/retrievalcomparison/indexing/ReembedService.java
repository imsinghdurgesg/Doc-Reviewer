package com.app.docreview.retrievalcomparison.indexing;

import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Rebuilds a standard version's vectors from ALREADY-EXTRACTED requirements. Architecture §13.4, §19.2.
 *
 * <p><strong>Why re-embedding is separate from reindexing.</strong> A taxonomy change, an embedding
 * model change, or a chunk-payload change invalidates the VECTORS while leaving the extracted
 * requirements perfectly valid. Re-running the full indexing job would re-extract every requirement
 * through the model — the expensive, non-deterministic part — to arrive at requirement text that is
 * already on disk. On a corpus of 11 versions that is roughly 288 requirements re-extracted for no
 * gain, and §12.1 says extraction is never re-run per comparison for exactly this reason.
 *
 * <p><strong>Extraction output is not touched.</strong> This reads the persisted requirement set and
 * writes vectors. Requirement ids, normalized text, clause structure, and provenance all survive, so a
 * finding recorded before the re-embed still resolves to the same requirement afterwards. That is what
 * makes this safe to run against a corpus with existing comparisons.
 *
 * <p><strong>No model call at all.</strong> Profile metadata derivation is skipped: it is an LLM call
 * whose output is already stored on the standard. Re-deriving it could also CHANGE the category and
 * tags, which would silently move the profile vector for reasons unrelated to the re-embed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReembedService {

    private final RequirementSetVersionRepository setVersions;
    private final StandardRequirementRepository requirements;
    private final VectorIndexingService indexing;

    /**
     * @param metadata the standard's descriptive metadata, read from review-core rather than re-derived
     * @return what happened, for the caller to report
     */
    public Result reembed(String standardId, String standardVersionId, String title,
                          String description, VectorIndexingService.StandardMetadata metadata) {

        if (standardVersionId == null || standardVersionId.isBlank()) {
            return new Result(standardVersionId, false, 0, "standardVersionId is required");
        }

        // The most recent requirement set for this version. Ordered descending, so the first is
        // current — re-embedding an older set would resurrect superseded requirements as live vectors.
        var sets = setVersions.findByStandardVersionIdOrderByCreatedAtDesc(standardVersionId);
        if (sets.isEmpty()) {
            // Not an error worth failing on: a version may legitimately have no extracted set yet
            // (NEEDS_REVIEW, or never indexed). The profile is still worth re-embedding.
            indexing.indexStandardProfile(standardId, standardVersionId, title, description, null,
                    metadata);
            log.info("Re-embedded profile only for {} (no requirement set on record)",
                    standardVersionId);
            return new Result(standardVersionId, true, 0,
                    "Profile re-embedded; no extracted requirement set to index");
        }

        var current = sets.getFirst();
        var docs = requirements.findByStandardVersionIdAndRequirementSetVersion(
                standardVersionId, current.getRequirementSetVersion());

        // Profile first, then requirements. Order matters only for observability: if the requirement
        // embedding fails partway, the profile is already consistent and the version stays selectable.
        indexing.indexStandardProfile(standardId, standardVersionId, title, description, null,
                metadata);

        // indexRequirements deletes this version's requirement vectors before writing, so a re-embed
        // cannot leave a mix of old and new points. That guarantee is what makes this idempotent.
        indexing.indexRequirements(docs, metadata);

        log.info("Re-embedded {} ({} requirement(s) from set {})",
                standardVersionId, docs.size(), current.getRequirementSetVersion());
        return new Result(standardVersionId, true, docs.size(), null);
    }

    /**
     * @param requirementsIndexed material requirements are a subset of these; the vector count can be
     *                            lower, which is expected rather than a discrepancy
     */
    public record Result(
            String standardVersionId,
            boolean succeeded,
            int requirementsIndexed,
            String message) {
    }
}
