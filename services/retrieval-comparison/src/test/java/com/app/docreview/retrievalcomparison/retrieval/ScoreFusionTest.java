package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.contracts.retrieval.RetrievalScores;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reciprocal Rank Fusion. Architecture §13.3.
 *
 * <p>The behaviour worth pinning is that a result found by ONE retriever still scores — that is the
 * entire reason for running two.
 */
class ScoreFusionTest {

    private final ScoreFusion fusion = new ScoreFusion();

    @Test
    @DisplayName("a result ranked first by both retrievers wins")
    void agreementRanksHighest() {
        var fused = fusion.fuse(List.of("A", "B"), List.of("A", "C"), Map.of(), Map.of());
        assertThat(fused.keySet().iterator().next()).isEqualTo("A");
    }

    @Test
    @DisplayName("a semantic-only hit still scores, so paraphrases are not lost")
    void semanticOnlyStillScores() {
        // The case hybrid retrieval exists for: a requirement phrased differently from the document.
        var fused = fusion.fuse(List.of("SEMANTIC-ONLY"), List.of(), Map.of(), Map.of());
        assertThat(fused).containsKey("SEMANTIC-ONLY");
        assertThat(fused.get("SEMANTIC-ONLY").finalRetrievalScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a keyword-only hit still scores, so exact control IDs are not lost")
    void keywordOnlyStillScores() {
        // "AC-2" can be invisible to semantic search and obvious to keyword search.
        var fused = fusion.fuse(List.of(), List.of("AC-2"), Map.of(), Map.of());
        assertThat(fused.get("AC-2").finalRetrievalScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("results are ordered by fused score, highest first")
    void orderedDescending() {
        var fused = fusion.fuse(List.of("A", "B", "C"), List.of("C", "B", "A"), Map.of(), Map.of());
        var scores = fused.values().stream().map(RetrievalScores::finalRetrievalScore).toList();
        assertThat(scores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("component scores are retained for diagnosis")
    void keepsComponentScores() {
        // "semantic 0.88 / keyword 0.02" and "semantic 0.31 / keyword 0.95" are different problems.
        // A single fused number cannot tell them apart.
        var fused = fusion.fuse(List.of("A"), List.of("A"),
                Map.of("A", 0.88), Map.of("A", 0.02));
        assertThat(fused.get("A").semanticScore()).isEqualTo(0.88);
        assertThat(fused.get("A").keywordScore()).isEqualTo(0.02);
    }

    @Test
    @DisplayName("the strategy version is stamped on every result")
    void stampsStrategyVersion() {
        // Without this, a comparison run before a fusion change cannot be distinguished from one
        // run after — and §19.2's reproducibility guarantee becomes unverifiable.
        var fused = fusion.fuse(List.of("A"), List.of(), Map.of(), Map.of());
        assertThat(fused.get("A").retrievalStrategyVersion()).isEqualTo("RRF-V1");
    }

    @Test
    @DisplayName("fusion is deterministic")
    void deterministic() {
        var first = fusion.fuse(List.of("A", "B"), List.of("B", "A"), Map.of(), Map.of());
        var second = fusion.fuse(List.of("A", "B"), List.of("B", "A"), Map.of(), Map.of());
        assertThat(first.get("A").finalRetrievalScore())
                .isEqualTo(second.get("A").finalRetrievalScore());
    }

    @Test
    @DisplayName("a reranker replaces the fused score rather than blending with it")
    void rerankerReplaces() {
        // A cross-encoder is strictly better informed than rank fusion; averaging would dilute the
        // better signal. The fused value stays available for diagnostics.
        var base = fusion.fuse(List.of("A"), List.of("A"), Map.of("A", 0.7), Map.of("A", 0.4)).get("A");
        var reranked = fusion.withReranker(base, 0.93);
        assertThat(reranked.finalRetrievalScore()).isEqualTo(0.93);
        assertThat(reranked.semanticScore()).isEqualTo(0.7);
        assertThat(reranked.retrievalStrategyVersion()).contains("RERANK");
    }

    @Test
    @DisplayName("RRF scores are small by construction; they are ordering, not probability")
    void scoresAreRelativeNotAbsolute() {
        // A first-place result scores 1/61 ≈ 0.0164. Reading that as "1.6% relevant" and setting a
        // threshold of 0.5 would reject everything — the trap this test documents.
        var fused = fusion.fuse(List.of("A"), List.of(), Map.of(), Map.of());
        assertThat(fused.get("A").finalRetrievalScore()).isLessThan(0.02).isGreaterThan(0.016);
    }
}
