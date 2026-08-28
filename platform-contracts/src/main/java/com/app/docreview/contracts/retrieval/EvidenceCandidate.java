package com.app.docreview.contracts.retrieval;

import java.util.List;

/**
 * An uploaded-document passage that may satisfy a requirement. Architecture §15.2.
 *
 * <p>Carries {@code spanIds} rather than text. Those are the IDs the model is permitted to cite
 * (§16.2), which is what makes fabricated evidence impossible: an ID that was never supplied simply
 * fails validation.
 *
 * @param expandedContext structural context added around the chunk (§15.3) — parent heading,
 *                        adjacent paragraph, table headers. Bounded on purpose: a requirement is
 *                        judged on its passage plus enough surroundings to read it, not on the
 *                        whole document.
 */
public record EvidenceCandidate(
        String chunkId,
        String documentVersionId,
        List<String> spanIds,
        List<String> sectionPath,
        String text,
        Integer pageStart,
        Integer pageEnd,
        RetrievalScores scores,
        String expandedContext
) {}
