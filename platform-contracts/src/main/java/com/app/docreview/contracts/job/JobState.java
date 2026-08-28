package com.app.docreview.contracts.job;

/**
 * Lifecycle of an internal job. Architecture §21.
 *
 * <p>{@link #PARTIALLY_COMPLETED} is the important one: a comparison over 200 requirements where
 * three evaluations failed is not a failed comparison. Collapsing it to FAILED discards 197 valid
 * findings; calling it COMPLETED hides that something was not evaluated. §18.4 requires the partial
 * state to be visible to the user.
 */
public enum JobState {
    PENDING,
    DISPATCHED,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED
}
