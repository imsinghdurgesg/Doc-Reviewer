package com.app.docreview.reviewcore.standards.domain;

/**
 * Lifecycle of a standard version. Architecture §8.1.
 *
 * <p>Only {@link #ACTIVE} versions participate in comparisons. The distinction between
 * {@link #DEPRECATED} and {@link #ARCHIVED} matters for reproducibility: a historical comparison must
 * still resolve the exact version it used (§19.2), so versions are never deleted — they stop being
 * eligible for NEW comparisons instead.
 */
public enum StandardLifecycle {
    /** Uploaded, not yet indexed or reviewed. Not comparable. */
    DRAFT,
    /** Indexed, reviewed, and eligible for new comparisons. */
    ACTIVE,
    /** Superseded. Still resolvable for historical comparisons, not offered for new ones. */
    DEPRECATED,
    /** Retained for audit only. */
    ARCHIVED
}
