package com.app.docreview.contracts.requirement;

import com.app.docreview.contracts.comparison.UnmappedStandardObligation;
import com.app.docreview.contracts.enums.RequirementIntegrityStatus;

import java.time.Instant;
import java.util.List;

/**
 * A governed statement that an extracted requirement is or is not complete. Architecture §12.7, §18.3.
 *
 * <p><strong>Governed, not inferred.</strong> Like a condition-role override, this is a reviewed decision
 * stored alongside the extracted data rather than a property recomputed from it. That matters for the
 * same reason: the extraction it describes is frozen, and a decision recomputed at read time would
 * silently re-explain itself as the rules changed.
 *
 * <p>It is also the reason this mechanism is safe where the model-reported version was not. There is one
 * record per affected requirement, written once and reviewable, so a misjudgement affects exactly the
 * requirement it names — it cannot spread across a comparison run.
 *
 * @param requirementId         the requirement this describes
 * @param standardVersionId     part of the key: requirement ids recur across standards, so the id alone
 *                              does not identify a requirement
 * @param requirementSetVersion the extraction generation this applies to. A record must NOT carry
 *                              forward to a later set — a future extraction may well have fixed the
 *                              defect, and applying a stale record would suppress a requirement that is
 *                              now complete.
 * @param integrityStatus       the decision
 * @param missingObligations    what the source states and the clauses omit, with verbatim text and the
 *                              spans containing it. Empty for {@code COMPLETE}.
 * @param reviewedBy            who or what decided. {@code SYSTEM_REVIEW_BATCH} for a derived import.
 * @param reviewBatchId         the batch, so a whole import can be identified and withdrawn
 * @param notes                 why, for the reviewer who sees the resulting finding
 * @param recordedAt            when
 */
public record RequirementIntegrityRecord(
        String requirementId,
        String standardVersionId,
        String requirementSetVersion,
        RequirementIntegrityStatus integrityStatus,
        List<UnmappedStandardObligation> missingObligations,
        String reviewedBy,
        String reviewBatchId,
        String notes,
        Instant recordedAt
) {

    /** Never null, so callers need not null-check before iterating. */
    public List<UnmappedStandardObligation> missingObligations() {
        return missingObligations == null ? List.of() : missingObligations;
    }

    /**
     * Whether this record blocks the requirement from reaching the comparison model.
     *
     * <p>Guarded on the obligations being present as well as the status: a record claiming incomplete
     * coverage while naming nothing gives a reviewer no evidence, and the honest response is to compare
     * the requirement normally rather than withhold it on an unsupported claim. That is the same rule the
     * withdrawn model-reported path failed — the difference is that here it fails safe, because the
     * requirement still gets compared.
     */
    public boolean blocksComparison() {
        return integrityStatus != null
                && !integrityStatus.comparisonEligible()
                && !missingObligations().isEmpty();
    }

    /** Whether this record applies to the given requirement in the given set. */
    public boolean appliesTo(String requirement, String standardVersion, String setVersion) {
        return requirementId != null
                && requirementId.equals(requirement)
                && standardVersionId != null
                && standardVersionId.equals(standardVersion)
                && requirementSetVersion != null
                && requirementSetVersion.equals(setVersion);
    }
}
