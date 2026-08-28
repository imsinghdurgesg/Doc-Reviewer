package com.app.docreview.contracts.job;

/**
 * The kinds of background work dispatched over internal HTTP. Architecture §9.2, §20.3.
 *
 * <p>An enum rather than free-form strings because the type selects both the dispatch route and the
 * recovery behaviour. A typo in a string would produce a job that is written durably and then never
 * dispatched to anything — durable and invisible, the worst combination.
 */
public enum JobType {

    /** Parse, render, span, and chunk an uploaded process document (§14). */
    DOCUMENT_PROCESSING,

    /** Extract, validate, embed, and index requirements for a standard version (§12, §20.1). */
    STANDARD_INDEXING,

    /** Retrieve evidence and compare requirements for one comparison (§16, §20.2). */
    RETRIEVAL_COMPARISON
}
