package com.app.docreview.contracts.extraction;

/**
 * What a deterministic detector believes a source region is. Architecture §12.2.
 *
 * <p><strong>This is a candidate classification, not a verdict.</strong> The detector is a cheap
 * pre-filter that decides what is worth sending to the extraction model; the model decides what the
 * requirement actually is. Treating the detector's guess as authoritative is how a regex-only
 * pipeline ends up asserting that "rollback plan" is an accountable actor.
 *
 * <p>Only {@link #MANDATORY_REQUIREMENT}, {@link #PROHIBITION}, and applicable
 * {@link #CONDITIONAL_REQUIREMENT} are evaluated by default (§12.2). The remaining four exist so
 * definitions, examples, and rationale can be recognized and DELIBERATELY excluded rather than
 * silently promoted to controls — §12.8 rule 9 makes that a validation failure.
 */
public enum CandidateCategory {
    MANDATORY_REQUIREMENT,
    PROHIBITION,
    CONDITIONAL_REQUIREMENT,
    ADVISORY_GUIDANCE,
    DEFINITION,
    EXAMPLE,
    RATIONALE
}
