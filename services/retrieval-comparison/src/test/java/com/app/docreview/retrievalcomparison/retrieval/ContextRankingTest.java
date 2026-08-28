package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.contracts.retrieval.StandardCandidate;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.retrieval.document.AssessmentContext;
import com.app.docreview.retrievalcomparison.retrieval.document.StandardSelectionService;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment context must actually REORDER candidates. Architecture §15.1.
 *
 * <p><strong>The defect this pins.</strong> {@code AssessmentContext.weight} was applied only to the
 * threshold test, while {@code maxResults} and the near-tie bar were evaluated in raw score order. So a
 * context could DROP a candidate but never promote one: a standard boosted for a matching department
 * that sat fourth by raw score stayed fourth, and with {@code maxResults} smaller than that, it was
 * never selected. The weighting code ran, the numbers changed, and the output was identical — which is
 * why this needs a test rather than an inspection.
 *
 * <p>That mattered the moment document-side inference started supplying real values: the entire point of
 * inferring a department is to promote the standards owned by it.
 *
 * <p>The companion property — that promotion is BOUNDED and cannot overturn relevance — lives in
 * {@link AssessmentContextTest#weightCannotOverturnRelevance}. Both are needed: this one proves the
 * signal works, that one proves it cannot run away.
 */
class ContextRankingTest {

    private static final String DEPT = "Procurement";

    /** Returns fixed hits, so the test exercises ranking rather than vector search. */
    private static StandardSelectionService serviceReturning(
            List<VectorStorePort.SearchHit> hits) {

        EmbeddingGateway embeddings = new EmbeddingGateway() {
            @Override
            public List<float[]> embed(List<String> inputs) {
                return List.of(new float[]{0.1f, 0.2f});
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public String modelAlias() {
                return "test-embedding";
            }
        };

        VectorStorePort vectorStore = new VectorStorePort() {
            @Override
            public void ensureCollection(VectorCollection collection, int dimensions,
                                         String distanceMetric) {
            }

            @Override
            public void upsert(VectorCollection collection, String id, float[] vector,
                               Map<String, Object> payload) {
            }

            @Override
            public void upsertBatch(VectorCollection collection, List<VectorPoint> points) {
            }

            @Override
            public List<SearchHit> search(VectorCollection collection, float[] queryVector,
                                          int limit, Map<String, Object> filter, Double minScore) {
                return hits;
            }

            @Override
            public void deleteByFilter(VectorCollection collection, Map<String, Object> filter) {
            }
        };

        return new StandardSelectionService(embeddings, vectorStore, new ScoreFusion());
    }

    /** A hit carrying department metadata. Nulls are permitted — most standards are partly labelled. */
    private static VectorStorePort.SearchHit hit(String versionId, double score, String department) {
        var payload = new HashMap<String, Object>();
        payload.put("standardId", "std-" + versionId);
        payload.put("standardVersionId", versionId);
        payload.put("standardKey", "KEY-" + versionId);
        payload.put("title", "Standard " + versionId);
        payload.put("owner", "Owner");
        // HashMap, not Map.of: a null department is the common case and Map.of rejects nulls.
        payload.put("department", department);
        return new VectorStorePort.SearchHit(versionId, score, payload);
    }

    private static List<String> selectedIds(List<StandardCandidate> candidates) {
        return candidates.stream()
                .filter(StandardCandidate::selected)
                .map(StandardCandidate::standardVersionId)
                .toList();
    }

    @Nested
    @DisplayName("reordering")
    class Reordering {

        @Test
        @DisplayName("a matching department promotes a candidate past a closely-ranked one")
        void matchingDepartmentPromotes() {
            // 0.62 with the right department vs 0.66 with the wrong one. Weighted: 0.62*1.10 = 0.682
            // against 0.66*0.90 = 0.594. The match should now win.
            //
            // Before the fix this returned "v-wrong": the weights were computed, compared against the
            // threshold, and then ignored for ordering.
            var candidates = serviceReturning(List.of(
                    hit("v-wrong", 0.66, "Finance"),
                    hit("v-right", 0.62, DEPT)))
                    .selectStandards("a purchasing procedure", 1,
                            new AssessmentContext(DEPT, null, null, null));

            assertThat(selectedIds(candidates)).containsExactly("v-right");
        }

        @Test
        @DisplayName("the promoted candidate is returned first, not merely selected")
        void orderIsVisibleToTheReviewer() {
            // The response order IS the presentation order on the selection screen. A reviewer reading
            // top-down must see the promoted standard first, or the ranking is invisible to them.
            var candidates = serviceReturning(List.of(
                    hit("v-wrong", 0.66, "Finance"),
                    hit("v-right", 0.62, DEPT)))
                    .selectStandards("profile", 2,
                            new AssessmentContext(DEPT, null, null, null));

            assertThat(candidates.getFirst().standardVersionId()).isEqualTo("v-right");
        }

        @Test
        @DisplayName("a bounded boost cannot overturn a decisive relevance gap")
        void boostCannotOverturnRelevance() {
            // THE safety property, exercised through the real ranking path rather than the weight
            // function alone. 0.50 with every label matching must not beat 0.80 with none.
            var candidates = serviceReturning(List.of(
                    hit("v-relevant", 0.80, "Finance"),
                    hit("v-labelled", 0.50, DEPT)))
                    .selectStandards("profile", 1,
                            new AssessmentContext(DEPT, null, null, null));

            assertThat(selectedIds(candidates)).containsExactly("v-relevant");
        }
    }

    @Nested
    @DisplayName("the automatic path is unchanged")
    class NoContext {

        @Test
        @DisplayName("with no context the raw order is preserved")
        void rawOrderPreserved() {
            // Every weight is 1.0, so sorting must be a no-op. This is the regression guard for the
            // single-standard automatic path, which must behave exactly as it did before reordering
            // was introduced.
            var candidates = serviceReturning(List.of(
                    hit("v-a", 0.82, "Finance"),
                    hit("v-b", 0.55, "Procurement"),
                    hit("v-c", 0.41, null)))
                    .selectStandards("profile", 3);

            assertThat(candidates.stream().map(StandardCandidate::standardVersionId))
                    .containsExactly("v-a", "v-b", "v-c");
            assertThat(selectedIds(candidates)).containsExactly("v-a");
        }
    }

    @Nested
    @DisplayName("the one-standard limit survives a context")
    class AutoSelectionLimit {

        @Test
        @DisplayName("a context does not let auto-selection pick a second standard")
        void limitOfOneHoldsUnderContext() {
            // Supplying a context sets isEmpty() false, which BYPASSES the near-tie gate. That gate is
            // one of two things keeping auto-selection to a single standard, and document-side
            // inference now supplies a context on the automatic path — so the remaining guard,
            // maxResults, has to be doing the work.
            //
            // If it were not, an inferred department would silently restore the 3-standard behaviour
            // that produced 118 findings instead of 65, with the same control id reported three times.
            var candidates = serviceReturning(List.of(
                    hit("v-a", 0.80, DEPT),
                    hit("v-b", 0.80, DEPT),
                    hit("v-c", 0.79, DEPT)))
                    .selectStandards("profile", 1,
                            new AssessmentContext(DEPT, null, null, null));

            assertThat(selectedIds(candidates)).hasSize(1);
        }

        @Test
        @DisplayName("the standards not selected are still reported with a reason")
        void unselectedRemainVisible() {
            // §15.1: a reviewer must be able to see that a standard was considered. Under a context the
            // rejection reason is "beyond the top N", not a relevance failure — the distinction matters
            // because one is tuning and the other is retrieval.
            var candidates = serviceReturning(List.of(
                    hit("v-a", 0.80, DEPT),
                    hit("v-b", 0.79, DEPT)))
                    .selectStandards("profile", 1,
                            new AssessmentContext(DEPT, null, null, null));

            assertThat(candidates).hasSize(2);
            assertThat(candidates.get(1).rejectionReason())
                    .as("an excluded runner-up must say why")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("vocabulary harvesting")
    class VocabularyHarvesting {

        @Test
        @DisplayName("only labels that exist on candidates are offered")
        void harvestsFromCandidates() {
            // The model may only choose from these. Anything else would be compared for exact equality
            // against a standard's label, fail, and PENALISE the standard it was meant to help.
            var service = serviceReturning(List.of());

            var vocabulary = service.vocabularyOf(List.of(
                    hit("v-a", 0.8, "Procurement"),
                    hit("v-b", 0.7, "Finance"),
                    hit("v-c", 0.6, null)));

            assertThat(vocabulary.departments()).containsExactly("Procurement", "Finance");
        }

        @Test
        @DisplayName("duplicates collapse case-insensitively, keeping the first spelling")
        void duplicatesCollapse() {
            // "Finance" and "finance" are one choice, not two. Offering both invites the model to pick
            // the variant that does not match the standard it is trying to select.
            var vocabulary = serviceReturning(List.of()).vocabularyOf(List.of(
                    hit("v-a", 0.8, "Finance"),
                    hit("v-b", 0.7, "finance"),
                    hit("v-c", 0.6, "  FINANCE  ")));

            assertThat(vocabulary.departments()).containsExactly("Finance");
        }

        @Test
        @DisplayName("an unlabelled corpus yields an empty vocabulary, not a list of nulls")
        void unlabelledCorpusIsEmpty() {
            // The gateway skips the model call entirely in this case: with nothing to match against,
            // every possible answer is a mismatch penalty.
            var vocabulary = serviceReturning(List.of()).vocabularyOf(List.of(
                    hit("v-a", 0.8, null),
                    hit("v-b", 0.7, "   ")));

            assertThat(vocabulary.departments()).isEmpty();
            assertThat(vocabulary.isEmpty()).isTrue();
        }
    }
}
