package com.app.docreview.retrievalcomparison.spans;

import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.evidence.EvidenceSpan;

import java.util.List;

/**
 * Reads evidence spans owned by another service. Architecture §9.8, §22.2.
 *
 * <p>Spans live in the {@code document_processing} database, and §22 forbids one service reading
 * another's collections directly. This port is the boundary: retrieval-comparison asks for spans by
 * ID and receives them, without knowing whether they arrive over HTTP, from a cache, or from an
 * extracted artifact in MinIO.
 *
 * <p>A named interface rather than a {@code Function<List<String>, List<EvidenceSpan>>}: a raw
 * functional type has no name in a stack trace, cannot be resolved by Spring without an explicit
 * {@code @Bean}, and tells a reader nothing about what it fetches or from where.
 */
public interface EvidenceSpanReader {

    /**
     * Loads spans by ID.
     *
     * <p>Implementations must return only spans that exist. A missing ID is silently absent from the
     * result rather than an error, because the caller validates coverage anyway — and a partial read
     * that throws would fail a whole extraction over one stale reference.
     */
    List<EvidenceSpan> byIds(String documentVersionId, List<String> spanIds);

    /**
     * Loads every span of a document version, in reading order.
     *
     * <p>A distinct operation from {@link #byIds} rather than "byIds with an empty list": an empty ID
     * list legitimately means "no spans requested" and returns empty, so overloading it to mean
     * "all spans" would make the two cases indistinguishable at the call site. Requirement
     * extraction needs the whole document (§12.2 scans it for candidates), whereas comparison needs
     * specific spans — opposite defaults, so they are separate methods.
     */
    List<EvidenceSpan> allForVersion(String documentVersionId);

    /**
     * Loads every semantic chunk of a document version.
     *
     * <p>Chunks are the retrieval unit (§13.2) and spans are the citation unit, so comparison needs
     * both — chunks to embed and search, spans to quote. Kept on this port because both are owned by
     * document-processing (§22.2) and must be read through the same boundary.
     */
    List<SemanticChunk> chunksForVersion(String documentVersionId);
}
