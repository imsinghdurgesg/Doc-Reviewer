package com.app.docreview.contracts.document;

import java.util.List;

/**
 * A retrieval unit built from evidence spans. Architecture §11.4.
 *
 * <p>Chunks exist for RETRIEVAL; spans exist for EVIDENCE. Keeping them separate is what lets a
 * chunk be big enough to embed meaningfully while the citation stays as small as the passage that
 * actually supports the finding (§11.3: "prefer the smallest useful passage").
 *
 * <p>{@code spanIds} is the link back. A chunk that matched a query can name exactly which spans it
 * was built from, so the model receives span IDs it is allowed to cite (§16.2).
 *
 * <p>{@code chunkingStrategyVersion} is recorded because a chunking change alters retrieval results.
 * Without it, a comparison run before the change cannot be distinguished from one run after, and
 * §19.2's reproducibility guarantee is unverifiable.
 */
public record SemanticChunk(
        String chunkId,
        String documentVersionId,
        String chunkType,
        List<String> sectionPath,
        List<String> spanIds,
        String text,
        Integer pageStart,
        Integer pageEnd,
        int tokenCount,
        String chunkingStrategyVersion
) {}
