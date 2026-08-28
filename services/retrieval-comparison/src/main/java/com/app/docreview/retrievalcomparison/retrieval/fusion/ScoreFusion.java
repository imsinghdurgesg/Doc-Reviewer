package com.app.docreview.retrievalcomparison.retrieval.fusion;

import com.app.docreview.contracts.retrieval.RetrievalScores;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines semantic and keyword rankings. Architecture §13.3.
 *
 * <p>Uses Reciprocal Rank Fusion, which combines RANKS rather than scores. That choice matters: a
 * cosine similarity of 0.82 and a BM25 score of 14.3 are not on the same scale, have no shared
 * ceiling, and vary in distribution per query. Any weighted sum of the two is arbitrary and shifts
 * meaning between queries. Ranks are comparable by construction.
 *
 * <p><strong>The consequence to keep in mind:</strong> an RRF score is a relative ordering signal,
 * not an absolute relevance measure. A top result scoring 0.032 is not "3% relevant" — it is simply
 * first. Thresholds must therefore be tuned against observed distributions, never set from
 * intuition about what a probability should look like.
 *
 * <p>Deterministic and versioned: identical inputs always produce identical output, and
 * {@link #STRATEGY_VERSION} is recorded on every result so a comparison run under different fusion
 * is distinguishable (§13.3).
 */
@Component
public class ScoreFusion {

    /** Bump on ANY change here: recorded per retrieval and load-bearing for §19.2. */
    public static final String STRATEGY_VERSION = "RRF-V1";

    /**
     * RRF damping constant.
     *
     * <p>60 is the value from the original Cormack et al. formulation and the de facto default. It
     * controls how quickly rank contribution decays: lower values let the top few results dominate,
     * higher values flatten the curve. It is not tuned here because tuning it without an evaluation
     * set is guessing.
     */
    private static final double RRF_K = 60.0;

    /**
     * Fuses two ranked lists of IDs.
     *
     * <p>An ID present in only one list still scores — that is the point. A requirement phrased with
     * an exact control ID ("AC-2") may be invisible to semantic search and obvious to keyword
     * search, and vice versa for a paraphrase. Requiring presence in both would discard the cases
     * where hybrid retrieval earns its keep.
     *
     * @param semanticRanked ids in descending semantic relevance
     * @param keywordRanked  ids in descending keyword relevance
     * @return fused scores per id, highest first
     */
    public Map<String, RetrievalScores> fuse(List<String> semanticRanked, List<String> keywordRanked,
                                             Map<String, Double> rawSemanticScores,
                                             Map<String, Double> rawKeywordScores) {

        Map<String, Double> fused = new LinkedHashMap<>();
        accumulate(fused, semanticRanked);
        accumulate(fused, keywordRanked);

        // Sorted descending so the caller can apply top-k without re-sorting, and so a threshold cut
        // is a prefix rather than a scan.
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                        (map, e) -> map.put(e.getKey(), new RetrievalScores(
                                rawSemanticScores.get(e.getKey()),
                                rawKeywordScores.get(e.getKey()),
                                null,
                                e.getValue(),
                                STRATEGY_VERSION)),
                        LinkedHashMap::putAll);
    }

    private void accumulate(Map<String, Double> fused, List<String> ranked) {
        if (ranked == null) {
            return;
        }
        for (int i = 0; i < ranked.size(); i++) {
            // 1-based rank: rank 0 would make the first result's contribution 1/60 instead of 1/61,
            // a small but gratuitous inconsistency with the published formulation.
            double contribution = 1.0 / (RRF_K + i + 1);
            fused.merge(ranked.get(i), contribution, Double::sum);
        }
    }

    /**
     * Applies a reranker score on top of a fused result.
     *
     * <p>The reranker REPLACES the final score rather than blending with it. A cross-encoder judges
     * query-document relevance directly and is strictly better informed than rank fusion; averaging
     * the two would dilute the better signal with the worse one. The fused score is retained for
     * diagnostics (§13.3).
     */
    public RetrievalScores withReranker(RetrievalScores base, double rerankerScore) {
        return new RetrievalScores(
                base.semanticScore(), base.keywordScore(), rerankerScore,
                rerankerScore, STRATEGY_VERSION + "+RERANK");
    }
}
