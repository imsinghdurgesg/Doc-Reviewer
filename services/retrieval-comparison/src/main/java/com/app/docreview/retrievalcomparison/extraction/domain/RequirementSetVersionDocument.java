package com.app.docreview.retrievalcomparison.extraction.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One immutable extraction run over a standard version. Architecture §12.9.
 *
 * <p>Re-extraction creates a NEW version rather than replacing the old one, and existing comparisons
 * keep the version they used. Without that, improving the extractor would silently rewrite the
 * requirements that historical findings refer to — findings would cite requirements that no longer
 * exist, or worse, requirements whose wording changed underneath them.
 *
 * <p>The version fields recorded here are exactly the inputs that can change extraction output. If
 * any of them differs, the resulting requirement set is not comparable to a previous one.
 */
@Document(collection = "requirement_set_versions")
@CompoundIndex(name = "reqset_by_version", def = "{'standardVersionId': 1, 'createdAt': -1}")
@Getter
@NoArgsConstructor
public class RequirementSetVersionDocument {

    @Id
    private String id;

    private String standardVersionId;
    private String requirementSetVersion;

    private String requirementExtractionModelAlias;
    private String requirementExtractionPromptVersion;
    private String requirementExtractionSchemaVersion;
    private String candidateDetectorVersion;
    private String chunkingStrategyVersion;

    @Setter private int candidateCount;
    @Setter private int requirementCount;
    @Setter private int materialRequirementCount;
    @Setter private int rejectedCount;

    /**
     * Sections that produced no requirement because they read as guidance, a definition, or an
     * example (§12.2).
     *
     * <p>Not a failure — but an author who meant a clause to be binding needs to know it was skipped,
     * or the omission is invisible until a comparison quietly never checks it.
     */
    @Setter private int skippedCandidateCount;

    /** Token spend for this run. §10.4 requires usage to be recorded; also the cost signal. */
    @Setter private long promptTokens;
    @Setter private long completionTokens;

    @Setter private StandardRequirementDocument.ValidationStatus validationStatus;
    /** Reasons, when the set needs review. Structural only — never requirement text (§35). */
    @Setter private java.util.List<String> validationErrors;

    private Instant createdAt;
    @Setter private Instant completedAt;

    public RequirementSetVersionDocument(String standardVersionId, String requirementSetVersion,
                                         String modelAlias, String promptVersion,
                                         String schemaVersion, String detectorVersion,
                                         String chunkingStrategyVersion) {
        this.id = "%s:%s".formatted(standardVersionId, requirementSetVersion);
        this.standardVersionId = standardVersionId;
        this.requirementSetVersion = requirementSetVersion;
        this.requirementExtractionModelAlias = modelAlias;
        this.requirementExtractionPromptVersion = promptVersion;
        this.requirementExtractionSchemaVersion = schemaVersion;
        this.candidateDetectorVersion = detectorVersion;
        this.chunkingStrategyVersion = chunkingStrategyVersion;
        this.createdAt = Instant.now();
        this.validationErrors = java.util.List.of();
    }
}
