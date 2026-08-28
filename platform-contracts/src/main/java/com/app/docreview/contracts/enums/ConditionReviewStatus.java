package com.app.docreview.contracts.enums;

/**
 * How far a human review of a condition's role has got. Architecture §12.5, §19.2.
 *
 * <p><strong>Why a status rather than just a role.</strong> A review of 115 model-predicted
 * {@code SCOPE_GATE} conditions reclassified 113. Around 20 of those were defensible readings rather
 * than certainties — "for all remote access to production" may state a real boundary, or may merely
 * restate that the requirement universally governs remote production access, and the answer depends on
 * the standard's own scope.
 *
 * <p>Recording those as a confident role would turn a defensible interpretation into a false certainty.
 * The status keeps the uncertainty visible and routes it to whoever owns the policy.
 */
public enum ConditionReviewStatus {

    /**
     * A reviewer examined the condition and is confident in the role.
     *
     * <p>The only status whose role takes precedence over the model's, and the only one that may permit
     * exclusion — and even then only when {@code canDriveNotApplicable} is separately set.
     */
    CONFIRMED,

    /**
     * Reviewed, but the correct role depends on policy intent the reviewer could not settle.
     *
     * <p>Resolves to {@link ConditionRole#UNKNOWN} at runtime, so the requirement is still compared and
     * flagged for human review. Deliberately NOT the reviewer's best guess: a guess presented as
     * authoritative is worse than a recorded question, because nobody goes back to check it.
     */
    NEEDS_POLICY_OWNER,

    /**
     * The reviewer judged the extracted condition to be wrong — not a real condition at all.
     *
     * <p>For a truncated fragment or a mis-extraction. Resolves to UNKNOWN rather than removing the
     * condition, because the extracted record is immutable and the reviewer's judgement is a layer over
     * it, not an edit to it.
     */
    REJECTED,

    /**
     * A superseded override, kept for audit.
     *
     * <p>Never applied at runtime. Overrides are versioned by batch, and retiring rather than deleting
     * is what lets a completed comparison still name the decision it actually used (§19.2).
     */
    RETIRED;

    /** True when this override's reviewed role replaces the model's. */
    public boolean isAuthoritative() {
        return this == CONFIRMED;
    }

    /** True when this override is applied at all, in any form. */
    public boolean isActive() {
        return this != RETIRED;
    }
}
