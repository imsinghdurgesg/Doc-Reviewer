package com.app.docreview.contracts.enums;

/**
 * Where an effective condition role came from. Architecture §19.2.
 *
 * <p>Recorded on every applicability decision so a reader can tell a machine interpretation from a
 * governed human one. Without it, a reviewed classification and a model guess look identical in the
 * result — and the whole point of layering human review over extraction is that the difference matters.
 *
 * <p>Ordered by precedence, highest first, matching the resolution order in the effective-role service.
 */
public enum ConditionRoleSource {

    /** Confirmed by whoever owns the policy. The highest authority available. */
    POLICY_OWNER_OVERRIDE,

    /** Confirmed by a reviewer during a review batch. */
    REVIEWER_OVERRIDE,

    /**
     * Derived from structured extraction metadata rather than a role classification.
     *
     * <p>A {@code documentTypes} list is machine-decidable on its own terms, so it does not depend on
     * the model having named a role correctly.
     */
    STRUCTURED_METADATA,

    /** The model's own classification, with no human review recorded. */
    MODEL_EXTRACTED,

    /**
     * No usable classification.
     *
     * <p>Reached when an override says {@code NEEDS_POLICY_OWNER} or {@code REJECTED} — the reviewer
     * looked and could not settle it, which is different from nobody having looked.
     */
    UNRESOLVED;

    /** True when a human stands behind this role. */
    public boolean isHumanGoverned() {
        return this == POLICY_OWNER_OVERRIDE || this == REVIEWER_OVERRIDE;
    }
}
