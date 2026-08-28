package com.app.docreview.retrievalcomparison.extraction.domain;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.enums.Severity;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A published requirement. Architecture §13.1.
 *
 * <p>The {@code _id} is composite — {@code standardVersionId:requirementSetVersion:requirementId} —
 * which makes re-extraction naturally additive: a new requirement-set version writes new documents
 * rather than overwriting the set that existing comparisons were measured against (§12.9).
 *
 * <p>{@code severity} is set from standard metadata or administrator rules, NEVER from the model
 * (§17.7). It is a field on the persisted document precisely so its provenance is the pipeline
 * rather than the response.
 *
 * <p>No {@code @Data} and no class-level {@code @Setter} (§25): this holds standard text, so a
 * generated {@code toString()} would leak confidential content into logs.
 */
@Document(collection = "standard_requirements")
@CompoundIndex(name = "req_set_lookup",
        def = "{'standardVersionId': 1, 'requirementSetVersion': 1}")
@CompoundIndex(name = "req_material",
        def = "{'standardVersionId': 1, 'requirementSetVersion': 1, 'material': 1}")
@Getter
@NoArgsConstructor
public class StandardRequirementDocument {

    @Id
    private String id;

    private String standardId;
    private String standardVersionId;
    private String requirementSetVersion;
    private String requirementId;

    private String parentControlId;
    private String sourceControlId;
    private String title;
    private List<String> sourceSpanIds;
    private NormativeStrength normativeStrength;
    private String normalizedRequirement;
    private List<AtomicClause> atomicClauses;
    private List<String> expectedEvidenceHints;
    private double extractionConfidence;

    /**
     * Stated applicability conditions, persisted. Architecture §12.5.
     *
     * <p>Added when the Phase 2 applicability workflow began consuming it. Previously dropped on the
     * floor: the constructor discarded {@code source.applicability()} and {@code
     * toExtractedRequirement()} returned null, so a requirement scoped to one document type looked
     * unconditional and was compared against every document. The class comment predicted exactly this
     * moment — "so a future applicability filter adds the field deliberately instead of silently
     * reading null".
     *
     * <p>Null for requirement sets extracted before this field existed. The resolver treats null as
     * unconditional, which is the correct reading: nothing was recorded, so nothing was stated.
     */
    private Applicability applicability;

    /**
     * Whether this requirement is EVALUATED. Derived from {@link NormativeStrength}, not from the
     * model: only mandatory requirements, prohibitions, and applicable conditional requirements are
     * compared by default (§12.2). Reporting a recommendation as a gap trains reviewers to ignore
     * findings.
     */
    private boolean material;

    /** From standard metadata or administrator rules — never the model (§17.7). */
    @Setter private Severity severity;

    private ExtractionProvenance extraction;
    @Setter private ValidationStatus validationStatus;
    private Instant createdAt;

    public StandardRequirementDocument(String standardId, String standardVersionId,
                                       String requirementSetVersion,
                                       ExtractedRequirement source,
                                       Severity severity,
                                       ExtractionProvenance extraction,
                                       ValidationStatus validationStatus) {
        this.id = "%s:%s:%s".formatted(standardVersionId, requirementSetVersion, source.requirementId());
        this.standardId = standardId;
        this.standardVersionId = standardVersionId;
        this.requirementSetVersion = requirementSetVersion;
        this.requirementId = source.requirementId();
        this.parentControlId = source.parentControlId();
        this.sourceControlId = source.sourceControlId();
        this.title = source.title();
        this.sourceSpanIds = List.copyOf(source.sourceSpanIds());
        this.normativeStrength = source.normativeStrength();
        this.normalizedRequirement = source.normalizedRequirement();
        this.atomicClauses = List.copyOf(source.atomicClauses());
        this.expectedEvidenceHints = source.expectedEvidenceHints() == null
                ? List.of() : List.copyOf(source.expectedEvidenceHints());
        this.extractionConfidence = source.extractionConfidence();
        this.applicability = source.applicability();
        this.material = isMaterial(source.normativeStrength());
        this.severity = severity;
        this.extraction = extraction;
        this.validationStatus = validationStatus;
        this.createdAt = Instant.now();
    }

    private static boolean isMaterial(NormativeStrength strength) {
        return strength == NormativeStrength.MANDATORY
                || strength == NormativeStrength.PROHIBITION
                || strength == NormativeStrength.CONDITIONAL;
    }

    /**
     * Rebuilds the contract form for comparison. Architecture §16.2.
     *
     * <p>A comparison loads the persisted requirement set (§12.1 — extraction is never re-run per
     * comparison) and must hand the comparison engine an {@link ExtractedRequirement}. This is that
     * conversion, and it lives here because this class owns the flattening it reverses.
     *
     * <p><strong>{@code applicability} IS round-tripped</strong> as of the Phase 2 applicability
     * workflow. It was previously dropped, which made every conditional requirement look
     * unconditional — so a control scoped to one document type was compared against all of them.
     */
    public ExtractedRequirement toExtractedRequirement() {
        return new ExtractedRequirement(
                requirementId, parentControlId, sourceControlId, title,
                sourceSpanIds == null ? List.of() : sourceSpanIds,
                normativeStrength, normalizedRequirement,
                atomicClauses == null ? List.of() : atomicClauses,
                applicability,
                expectedEvidenceHints == null ? List.of() : expectedEvidenceHints,
                extractionConfidence);
    }

    /**
     * Everything needed to reproduce this extraction (§13.1, §12.9).
     *
     * <p>Without these, a requirement set cannot be attributed to the prompt, schema, and detector
     * that produced it — and §19.2's reproducibility guarantee becomes unverifiable.
     */
    public record ExtractionProvenance(
            String modelAlias,
            String promptVersion,
            String schemaVersion,
            String candidateDetectorVersion,
            Instant timestamp
    ) {}

    /** §12.8: extraction that fails validation twice is NEEDS_REVIEW, not published. */
    public enum ValidationStatus {
        VALIDATED,
        NEEDS_REVIEW,

        /**
         * Extracted to MEASURE the extractor, never to run a comparison. §12.9, §19.2.
         *
         * <p><strong>Why a status rather than a convention.</strong> A comparison selects the newest
         * VALIDATED set, so a shadow extraction saved as VALIDATED would silently become the live one —
         * replacing decisions a person already reviewed with a fresh model guess, which is the exact
         * outcome the override model exists to prevent. Naming the intent in the data means the
         * selection query excludes it by construction instead of by everyone remembering to.
         *
         * <p>A SHADOW set is otherwise a normal, immutable, fully-provenanced extraction — that is the
         * point. Scoring a run that was produced differently from a real one would measure the harness
         * rather than the extractor.
         */
        SHADOW;

        /**
         * Whether a comparison may evaluate against this set.
         *
         * <p>Deliberately a positive test on VALIDATED rather than {@code != SHADOW}: a future status
         * then defaults to unusable, so forgetting to update this cannot make an unvetted set live.
         */
        public boolean comparable() {
            return this == VALIDATED;
        }
    }
}
