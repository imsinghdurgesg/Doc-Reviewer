package com.app.docreview.retrievalcomparison.comparison.domain;

import com.app.docreview.contracts.comparison.UnmappedStandardObligation;
import com.app.docreview.contracts.enums.RequirementIntegrityStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A governed decision that an extracted requirement is incomplete. Architecture §12.7, §18.3.
 *
 * <p><strong>Why a stored record rather than a check at comparison time.</strong> Detecting the defect
 * requires comparing the requirement against its provenance spans, which the comparison path does not
 * load — and re-deriving it per comparison would make a heuristic silently decide, on every run, whether
 * a control gets evaluated. One reviewed row per affected requirement keeps the decision inspectable and
 * bounded: a wrong row affects exactly the requirement it names.
 *
 * <p>Scoped to {@code requirementSetVersion} deliberately. A record must not carry into a later
 * extraction generation — the whole point of the extraction fix is that a future set will not have the
 * defect, and a stale record would suppress a requirement that had been repaired.
 */
@Document(collection = "requirement_integrity_records")
@CompoundIndex(name = "integrity_identity",
        def = "{'standardVersionId': 1, 'requirementSetVersion': 1, 'requirementId': 1}",
        unique = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RequirementIntegrity {

    @Id
    private String id;

    private String standardVersionId;
    private String requirementSetVersion;
    private String requirementId;

    /** Carried so a reviewer sees what the requirement demands without a second lookup. */
    private String requirementTitle;

    private RequirementIntegrityStatus integrityStatus;

    /**
     * What the source states and the clauses omit — verbatim text plus the spans containing it.
     *
     * <p>This is what makes the record reviewable rather than an assertion. A record with an empty list
     * does NOT block comparison: an unsupported claim about our own extraction should not withhold a
     * control, so the failure mode is "compared normally", not "silently skipped".
     */
    private List<UnmappedStandardObligation> missingObligations;

    private String reviewedBy;
    private String reviewBatchId;
    private String notes;
    private Instant recordedAt;

    /** Whether this record withholds the requirement from the comparison model. */
    public boolean blocksComparison() {
        return integrityStatus != null
                && !integrityStatus.comparisonEligible()
                && missingObligations != null
                && !missingObligations.isEmpty();
    }
}
