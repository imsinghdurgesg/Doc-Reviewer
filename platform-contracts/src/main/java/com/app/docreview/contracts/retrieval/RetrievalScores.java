package com.app.docreview.contracts.retrieval;

/**
 * Component scores behind one retrieval result. Architecture §13.3.
 *
 * <p>Every component is retained, not just the final value. Two reasons, both operational:
 *
 * <ul>
 *   <li>When retrieval returns something surprising, "semantic 0.88 / keyword 0.02" and
 *       "semantic 0.31 / keyword 0.95" are different problems with different fixes. A single fused
 *       number cannot distinguish them.</li>
 *   <li>{@code retrievalStrategyVersion} pins how they were combined. Changing the fusion changes
 *       results, and without the version an old comparison cannot be told apart from a new one —
 *       which §19.2's reproducibility guarantee depends on.</li>
 * </ul>
 *
 * <p><strong>A retrieval score is relevance, never compliance</strong> (§15.1). It says a passage is
 * worth showing the model, not that the document satisfies anything. Treating a high score as
 * evidence of compliance is the single most tempting misuse of this number.
 *
 * @param semanticScore vector similarity, or null when semantic search did not run
 * @param keywordScore  lexical match, or null when keyword search did not run
 * @param rerankerScore present only when an approved reranker alias exists (§5.4)
 */
public record RetrievalScores(
        Double semanticScore,
        Double keywordScore,
        Double rerankerScore,
        double finalRetrievalScore,
        String retrievalStrategyVersion
) {}
