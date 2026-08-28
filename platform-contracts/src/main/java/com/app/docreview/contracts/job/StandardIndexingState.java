package com.app.docreview.contracts.job;

/**
 * Administrator-visible progress of standard indexing. Architecture §21.
 *
 * <p>Granular by design: indexing runs an LLM extraction pass per candidate group, so it can take
 * minutes. A single "processing" state gives an administrator no way to tell slow from stuck.
 *
 * <p>{@link #NEEDS_REVIEW} is a terminal state that is neither success nor failure — §12.8 requires
 * extraction that fails validation twice to land here rather than publish unverified controls.
 */
public enum StandardIndexingState {
    UPLOADED,
    PARSING_STANDARD,
    RENDERING_STANDARD,
    GENERATING_SPANS,
    GENERATING_CHUNKS,
    DETECTING_REQUIREMENT_CANDIDATES,
    EXTRACTING_REQUIREMENTS,
    VALIDATING_REQUIREMENTS,
    GENERATING_EMBEDDINGS,
    INDEXING_STANDARD,
    INDEXED,
    NEEDS_REVIEW,
    FAILED
}
