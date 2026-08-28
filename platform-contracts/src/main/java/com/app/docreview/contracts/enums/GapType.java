package com.app.docreview.contracts.enums;

/**
 * Why a {@code PARTIAL} verdict is partial. Architecture §17.2.
 *
 * <p>"Partial" alone is not actionable — a reviewer cannot tell whether the process is weaker,
 * mistimed, or simply undocumented. These name the specific deficiency so the recommended action
 * can be concrete.
 */
public enum GapType {
    /** Present but a lower bar than the standard requires. */
    WEAKER,
    /** Some required elements present, others absent. */
    INCOMPLETE,
    /** Applies only in a narrower set of circumstances than the standard. */
    CONDITIONAL_ONLY,
    /** Right action, wrong point in the lifecycle — e.g. approval after deployment. */
    TIMING_MISMATCH,
    /** Wrong actor — e.g. self-approval where independence is required. */
    RESPONSIBILITY_MISMATCH,
    /** The control exists but no evidence obligation is documented. */
    EVIDENCE_NOT_DOCUMENTED,
    /** Applies to a different population of systems or changes. */
    SCOPE_MISMATCH
}
