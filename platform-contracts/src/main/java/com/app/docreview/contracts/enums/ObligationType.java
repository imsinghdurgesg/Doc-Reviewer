package com.app.docreview.contracts.enums;

/**
 * The kind of obligation an extraction dropped. Architecture §12.7, §18.3.
 *
 * <p>Recorded so the extraction defect can be triaged by category rather than one requirement at a
 * time. The 11 live cases measured so far are all {@link #TIMING} or {@link #SEQUENCING} — deadlines
 * ("within one business day") and ordering constraints ("before execution") — which is a far more
 * actionable finding for the extractor than 11 unrelated anecdotes would be.
 */
public enum ObligationType {

    /** A deadline or frequency: "within one business day", "annually", "no later than". */
    TIMING,

    /** An ordering constraint: "before execution", "prior to deployment". */
    SEQUENCING,

    /** Who must act, where the clause list names the action but not the actor. */
    RESPONSIBILITY,

    /** A record or artefact that must exist, where no clause demands it. */
    EVIDENCE,

    /** A precondition that gates the obligation. */
    CONDITION,

    /** Anything else; requires the reason field to carry the explanation. */
    OTHER
}
