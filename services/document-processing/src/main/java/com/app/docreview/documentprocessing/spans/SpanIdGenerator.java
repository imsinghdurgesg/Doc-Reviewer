package com.app.docreview.documentprocessing.spans;

/**
 * Deterministic span identifiers. Architecture §11.3.
 *
 * <p>Span IDs are immutable within a document version, so they are DERIVED rather than random: a
 * re-parse of identical content must produce identical IDs, or every previously stored finding points
 * at spans that no longer exist.
 *
 * <p>The document version ID is part of the input, which is what makes cross-version citation
 * impossible by construction — a span ID from one version cannot accidentally validate against
 * another (§11.3, §16.5).
 */
public final class SpanIdGenerator {

    private SpanIdGenerator() {
    }

    /**
     * @param ordinal reading-order position of the node this span came from; combined with the
     *                version so IDs are stable and unique without a counter or a lookup
     */
    public static String spanId(String documentVersionId, int ordinal, int subIndex) {
        // Short, readable, and greppable in logs — an opaque UUID makes diagnosing a bad citation
        // considerably harder.
        String digest = TextHasher.hash(documentVersionId).substring(7, 15);
        return "SPAN-%s-%d-%d".formatted(digest, ordinal, subIndex);
    }

    public static String nodeId(String documentVersionId, int ordinal) {
        String digest = TextHasher.hash(documentVersionId).substring(7, 15);
        return "NODE-%s-%d".formatted(digest, ordinal);
    }

    public static String chunkId(String documentVersionId, int index) {
        String digest = TextHasher.hash(documentVersionId).substring(7, 15);
        return "CHUNK-%s-%d".formatted(digest, index);
    }
}
