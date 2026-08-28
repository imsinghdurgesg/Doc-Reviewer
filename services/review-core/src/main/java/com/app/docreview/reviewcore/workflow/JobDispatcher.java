package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.document.DocumentType;

import java.util.List;

/**
 * Dispatches background work to the worker services. Architecture §9.2.
 *
 * <p>An interface so application code never holds a worker URL or an HTTP client. §2 excludes a
 * message broker from the MVP, but the seam here is the one that would absorb adding one later: an
 * implementation that enqueues instead of posting requires no change above this line.
 *
 * <p><strong>Every method persists a durable job record before dispatching</strong> (§21). Callers
 * receive the job ID and observe completion through the job record, never by waiting on the call.
 */
public interface JobDispatcher {

    /**
     * Queues parsing, rendering, spans, and chunks for a document version (§14).
     *
     * @return the durable job ID
     */
    String dispatchDocumentProcessing(String documentVersionId, DocumentType documentType,
                                      String sourceBucket, String sourceObjectKey,
                                      String sourceMediaType);

    /** Queues requirement extraction and indexing for a standard version (§12, §20.1). */
    /**
     * @param profile descriptive metadata for the selection vector (§15.1); any field may be blank
     */
    String dispatchStandardIndexing(String standardId, String standardVersionId,
                                    String standardTitle, String chunkingStrategyVersion,
                                    StandardProfile profile);

    /**
     * Descriptive metadata that shapes which standard a document is matched to.
     *
     * <p>Grouped into a record rather than four more parameters: they are one concept, and a
     * four-string call site is where a caller silently swaps category for businessUnit.
     */
    record StandardProfile(String description, String category, String businessUnit,
                           String standardFamily, String documentType,
                           java.util.List<String> tags) {
        public static StandardProfile empty() {
            return new StandardProfile(null, null, null, null, null, java.util.List.of());
        }
    }

    /**
     * Queues evidence retrieval and comparison (§16, §20.2).
     *
     * @param standardVersionIds explicit user selection, or empty for automatic selection (§15.1)
     * @param guidance           optional reviewer assessment context, or null. Steers retrieval; it
     *                           never restricts which standards are eligible (§15.1)
     */
    String dispatchRetrievalComparison(String comparisonId, String documentVersionId,
                                       List<String> standardVersionIds, String guidance);
}
