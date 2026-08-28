package com.app.docreview.contracts.job;

/**
 * User-visible progress of a comparison. Architecture §21.
 *
 * <p>Named after what the user is waiting for, not after internal components. "Evaluating
 * requirement" is meaningful to a reviewer; "calling retrieval-comparison" is not.
 */
public enum ComparisonState {
    UPLOADED,
    PARSING_DOCUMENT,
    RENDERING_DOCUMENT,
    GENERATING_SPANS,
    GENERATING_CHUNKS,
    GENERATING_EMBEDDINGS,
    SEARCHING_STANDARDS,
    RETRIEVING_REQUIREMENT_EVIDENCE,
    EVALUATING_REQUIREMENT,
    AGGREGATING_CONTROLS,
    GENERATING_FINDINGS,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED
}
