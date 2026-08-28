package com.app.docreview.contracts.enums;

/**
 * What a condition on a requirement actually DOES. Architecture §12.5.
 *
 * <p><strong>Why this exists.</strong> Applicability treated "the requirement states a condition" as one
 * category, and on a real corpus 116 of 254 requirements carried one. But those conditions do not mean
 * the same thing:
 *
 * <pre>
 *   "For medium- and high-risk changes"   -> may determine whether the requirement applies
 *   "When technically feasible"            -> changes HOW it is met; it still applies
 *   "Within one business day"              -> a timing obligation; it still applies
 *   "Unless an approved exception exists"  -> exception handling; the control still applies
 *   "If testing fails, initiate rollback"  -> a triggered obligation the process must still define
 * </pre>
 *
 * <p>Only the first is a question of scope. Treating the rest as scope questions would flag most of a
 * standard for applicability review, and — worse — put them on the path that can reach
 * {@code NOT_APPLICABLE}. A requirement excluded because it said "when technically feasible" would be a
 * silently removed obligation.
 *
 * <p><strong>Only {@link #SCOPE_GATE} may ever produce {@code NOT_APPLICABLE}.</strong> That is enforced
 * in the resolver, not merely documented here: every other role resolves to APPLICABLE and is compared
 * WITH its condition, so the model judges the requirement as the standard actually words it.
 */
public enum ConditionRole {

    /**
     * The condition determines WHETHER the requirement applies at all.
     *
     * <p>The only role that can lead to exclusion, and then only against confirmed context that clearly
     * conflicts. "For leased hardware, return dates must be recorded" against a confirmed
     * purchased-hardware-only procedure is the shape that justifies it.
     */
    SCOPE_GATE,

    /**
     * The requirement applies, and the condition names the trigger that activates the obligation.
     *
     * <p>"If testing fails, initiate rollback" is a control the process must still define. Reading it as
     * a scope limit would excuse a document that has no rollback procedure at all.
     */
    CONDITIONAL_OBLIGATION,

    /**
     * The requirement applies, and the condition qualifies HOW it is satisfied.
     *
     * <p>"When technically feasible" and "where supported" bound the expected implementation, not the
     * obligation. The comparison needs the qualifier so a partial implementation is judged fairly, but
     * the requirement is in scope either way.
     */
    IMPLEMENTATION_QUALIFIER,

    /**
     * The requirement applies, and the condition describes a waiver route.
     *
     * <p>"Unless an approved exception exists" means the process should DEFINE how exceptions are
     * approved. Excluding the requirement would let a document with no exception process appear
     * compliant.
     */
    EXCEPTION_OR_WAIVER,

    /**
     * The requirement applies, and the condition sets timing or ordering.
     *
     * <p>"Within one business day", "prior to deployment". A deadline is part of the obligation, and a
     * document that acts eventually rather than within a day is partially aligned — not out of scope.
     */
    TEMPORAL_OR_SEQUENCE,

    /**
     * The role could not be determined.
     *
     * <p>Resolves to {@code UNCERTAIN} and is still compared. Deliberately NOT treated as
     * {@code SCOPE_GATE}: an unclassified condition must not inherit the one role capable of removing a
     * requirement from the review.
     */
    UNKNOWN;

    /**
     * True when this role may lead to {@code NOT_APPLICABLE}.
     *
     * <p>{@link #SCOPE_GATE} alone. This is the single point where the restriction is expressed, so a
     * new role added later cannot acquire exclusion power by omission.
     */
    public boolean canExclude() {
        return this == SCOPE_GATE;
    }

    /**
     * True when the condition should be surfaced to the comparison model.
     *
     * <p>Every role except {@code SCOPE_GATE}: those conditions shape what compliance LOOKS like, so the
     * model has to see them. A scope gate is answered before comparison and would only be noise in the
     * prompt.
     */
    public boolean informsComparison() {
        return this != SCOPE_GATE;
    }

    /**
     * A tolerant parse for values read back from storage or a model response.
     *
     * @return the matching role, or {@link #UNKNOWN} for null, blank, or unrecognised input — never
     *         {@code SCOPE_GATE}, so a malformed value cannot gain exclusion power
     */
    public static ConditionRole parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
