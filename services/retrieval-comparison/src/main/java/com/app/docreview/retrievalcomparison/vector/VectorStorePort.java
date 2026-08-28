package com.app.docreview.retrievalcomparison.vector;

import java.util.List;
import java.util.Map;

/**
 * Vector storage and search. Architecture §9.6.
 *
 * <p>An application-owned interface rather than Spring AI's {@code VectorStore}. §5.4 requires named
 * collections, payload filters, versioned score fusion, and per-component diagnostics; a generic
 * abstraction hides exactly those, and hiding them is what turns "why did retrieval return this?"
 * into an unanswerable question.
 */
public interface VectorStorePort {

    /**
     * Creates a collection if absent.
     *
     * <p>{@code dimensions} is fixed at creation and cannot be changed (§13.4). Calling this with a
     * different size than an existing collection must fail loudly rather than silently write
     * incompatible vectors.
     */
    void ensureCollection(VectorCollection collection, int dimensions, String distanceMetric);

    /**
     * @param id       stable and derived, so re-indexing the same content overwrites rather than
     *                 duplicating — otherwise every re-index doubles the search space
     * @param payload  filterable metadata; must never contain document text, which belongs in spans
     */
    void upsert(VectorCollection collection, String id, float[] vector, Map<String, Object> payload);

    void upsertBatch(VectorCollection collection, List<VectorPoint> points);

    /**
     * @param filter equality filters applied BEFORE scoring. Filtering after the fact is the classic
     *               vector-search bug: the top-k comes back, the filter removes most of it, and the
     *               caller silently receives two results where ten were requested.
     */
    List<SearchHit> search(VectorCollection collection, float[] queryVector, int limit,
                          Map<String, Object> filter, Double minScore);

    /** Deletes by payload filter. Used for document retention (§13.2). */
    void deleteByFilter(VectorCollection collection, Map<String, Object> filter);

    record VectorPoint(String id, float[] vector, Map<String, Object> payload) {}

    record SearchHit(String id, double score, Map<String, Object> payload) {}
}
