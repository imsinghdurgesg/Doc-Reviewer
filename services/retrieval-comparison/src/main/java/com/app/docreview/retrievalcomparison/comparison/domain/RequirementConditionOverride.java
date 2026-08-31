package com.app.docreview.retrievalcomparison.comparison.domain;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A human review decision layered over a model-extracted condition role. Architecture §12.5, §19.2.
 *
 * <p><strong>An override, never an edit.</strong> The extracted requirement set is immutable (§12.1), so
 * this sits beside it rather than modifying it. That is what lets a reader answer, later: what did the
 * model originally say, what did the reviewer change, why, and which classification did this particular
 * comparison actually use?
 *
 * <p><strong>Role and exclusion permission are SEPARATE decisions.</strong> {@code reviewedRole} says
 * what the condition does; {@code canDriveNotApplicable} says whether it may remove a requirement from a
 * review. A reviewer can be confident a condition is a scope gate and still not authorise exclusion on
 * it — the second is a governance decision with a worse failure mode, since a false exclusion produces a
 * clean-looking report that is wrong.
 *
 * <p><strong>Uncertainty is preserved, not resolved.</strong> Of 115 reviewed conditions, roughly 20 were
 * defensible readings rather than certainties. Those carry {@link ConditionReviewStatus#NEEDS_POLICY_OWNER}
 * and resolve to {@link ConditionRole#UNKNOWN} at runtime, so the requirement is still compared and
 * flagged — rather than a reviewer's best guess being presented as authoritative.
 */
@Document(collection = "requirement_condition_overrides")
// Lookup is always by the requirement a comparison is evaluating, and a stale RETIRED row must never
// shadow an active one — so the index covers the identity plus the batch.
@CompoundIndex(name = "condition_identity",
        def = "{'standardVersionId': 1, 'requirementSetVersion': 1, 'requirementId': 1, "
                + "'conditionText': 1, 'reviewBatchId': 1}",
        unique = true)
@Getter
@NoArgsConstructor
public class RequirementConditionOverride {

    @Id
    private String id;

    // --- identity: which extracted condition this decision applies to ---
    private String standardVersionId;
    private String requirementSetVersion;
    private String requirementId;

    /**
     * The condition text, verbatim, used as the match key.
     *
     * <p>Matched on text rather than an index because extraction does not assign stable condition IDs,
     * and a positional index would silently re-target if a re-extraction reordered the conditions.
     * Text is what a reviewer actually judged.
     */
    private String conditionText;

    // --- machine provenance, preserved ---
    /** What the model classified it as, kept so the diff against review is inspectable. */
    private ConditionRole extractedRole;
    /** The extraction prompt version that produced {@code extractedRole}. */
    private String extractionVersion;

    // --- the governed decision ---
    @Setter private ConditionRole reviewedRole;
    @Setter private ConditionReviewStatus reviewStatus;

    /**
     * Whether this condition may drive {@code NOT_APPLICABLE}.
     *
     * <p>Deliberately independent of {@code reviewedRole}. Even a CONFIRMED scope gate does not exclude
     * unless this is set — permission to remove a control from a review is a heavier decision than
     * naming what a clause does, and conflating them would make every confident classification an
     * exclusion authorisation.
     */
    @Setter private boolean canDriveNotApplicable;

    /** Why, in the reviewer's words. Required for anything other than a plain CONFIRMED agreement. */
    @Setter private String reviewerNotes;

    // --- audit ---
    private String reviewedBy;
    private Instant reviewedAt;
    /** Groups a review pass, so a whole batch can be versioned, superseded, or retired together. */
    private String reviewBatchId;
    /** The CSV the batch was imported from, hashed — the artifact stays evidence, not a live lookup. */
    private String sourceFileName;
    private String sourceSha256;

    /**
     * @param canDriveNotApplicable pass false unless exclusion is explicitly authorised. The safe
     *                              default matters: a reviewer confirming a role should not
     *                              accidentally grant exclusion power by omission.
     */
    public RequirementConditionOverride(
            String standardVersionId, String requirementSetVersion, String requirementId,
            String conditionText, ConditionRole extractedRole, String extractionVersion,
            ConditionRole reviewedRole, ConditionReviewStatus reviewStatus,
            boolean canDriveNotApplicable, String reviewerNotes,
            String reviewedBy, String reviewBatchId,
            String sourceFileName, String sourceSha256) {

        this.standardVersionId = standardVersionId;
        this.requirementSetVersion = requirementSetVersion;
        this.requirementId = requirementId;
        this.conditionText = conditionText;
        this.extractedRole = extractedRole;
        this.extractionVersion = extractionVersion;
        this.reviewedRole = reviewedRole;
        this.reviewStatus = reviewStatus;
        // Belt and braces: an unconfirmed override can never authorise exclusion, whatever the caller
        // passed. The two guards are independent for a reason — this one holds even if an importer is
        // wrong about the other.
        this.canDriveNotApplicable = canDriveNotApplicable && reviewStatus == ConditionReviewStatus.CONFIRMED;
        this.reviewerNotes = reviewerNotes;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = Instant.now();
        this.reviewBatchId = reviewBatchId;
        this.sourceFileName = sourceFileName;
        this.sourceSha256 = sourceSha256;
    }

    /**
     * Revises this review decision. Architecture §12.5.
     *
     * <p><strong>The single place a review decision changes.</strong> The CSV importer and the Admin UI
     * both come through here, so the exclusion rule cannot hold on one path and not the other — and a
     * third caller added later inherits it rather than reimplementing it.
     *
     * <p>Exclusion authority is recomputed from the incoming status, never carried over. A condition
     * confirmed as a scope gate and authorised to exclude, later reclassified to
     * CONDITIONAL_OBLIGATION, must lose that authority in the same edit: leaving it set would keep a
     * control excluded on the strength of a classification that no longer exists.
     *
     * <p>Every revision restamps actor, timestamp and batch. An amended decision with the original
     * author's name on it would misattribute the change, which matters most for the one field that can
     * remove a control from a review.
     *
     * @param authoriseExclusion the reviewer's explicit opt-in; honoured only for a CONFIRMED SCOPE_GATE
     */
    public void revise(ConditionRole reviewedRole, ConditionReviewStatus reviewStatus,
                       boolean authoriseExclusion, String reviewerNotes,
                       String reviewedBy, String reviewBatchId) {
        this.reviewedRole = reviewedRole;
        this.reviewStatus = reviewStatus;
        // FILE PROVENANCE IS CLEARED, not kept.
        //
        // These name the spreadsheet a decision came from, with its hash, so a reader can prove which
        // file produced it. Revising through the UI makes that pairing false: the row would still name
        // condrole-review-v1.csv while no longer matching any line in it. A stale file reference is a
        // worse lie than an absent one — it invites verification that will disagree.
        //
        // The audit trail survives in the fields that are restamped below: who decided, when, and under
        // which batch. What is lost is only the claim that a file supports it, which is exactly the
        // claim that stopped being true.
        this.sourceFileName = null;
        this.sourceSha256 = null;
        // BOTH conditions, recomputed. Confirming what a clause does is not the same as authorising it
        // to remove a control, so the caller's opt-in is necessary but never sufficient.
        this.canDriveNotApplicable = authoriseExclusion
                && reviewStatus == ConditionReviewStatus.CONFIRMED
                && reviewedRole == ConditionRole.SCOPE_GATE;
        this.reviewerNotes = reviewerNotes;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = Instant.now();
        this.reviewBatchId = reviewBatchId;
    }

    /**
     * The role this override contributes, or UNKNOWN when it settles nothing.
     *
     * <p>NEEDS_POLICY_OWNER and REJECTED both yield UNKNOWN: a reviewer who looked and could not decide
     * has produced information — that the question is open — and forcing it into a definite role would
     * discard exactly that.
     */
    public ConditionRole effectiveRole() {
        if (reviewStatus == ConditionReviewStatus.CONFIRMED) {
            return reviewedRole == null ? ConditionRole.UNKNOWN : reviewedRole;
        }
        return ConditionRole.UNKNOWN;
    }
}
