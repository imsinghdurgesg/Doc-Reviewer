package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.contracts.retrieval.StandardCandidate;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.retrieval.document.StandardSelectionService;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automatic standard selection. Architecture §15.1.
 *
 * <p>The failure this pins was observed on a real upload. An organization has several standards on
 * one subject — change management, release management, deployment — and all of them score
 * respectably against a change procedure. Selecting every one that cleared the relevance floor
 * measured a single document against three standards at once: 118 findings instead of 65, with
 * {@code CHG-01} appearing three times from three different sources. A reviewer reads that as
 * duplication and cannot tell which standard actually governs the document.
 */
class StandardSelectionServiceTest {

    /** Returns fixed hits, so the test exercises selection rather than vector search. */
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

    private static VectorStorePort.SearchHit hit(String versionId, double score) {
        return new VectorStorePort.SearchHit(versionId, score, Map.of(
                "standardId", "std-" + versionId,
                "standardVersionId", versionId,
                "standardKey", "KEY-" + versionId,
                "title", "Standard " + versionId,
                "owner", "Owner"));
    }

    private static List<String> selectedIds(List<StandardCandidate> candidates) {
        return candidates.stream()
                .filter(StandardCandidate::selected)
                .map(StandardCandidate::standardVersionId)
                .toList();
    }

    @Test
    @DisplayName("a clear winner is compared alone, not alongside weaker matches")
    void oneClearWinnerIsSelectedAlone() {
        // 0.82 vs 0.55: both clear the 0.35 floor, but the second is not remotely comparable.
        // Selecting both is what produced duplicate control ids from two standards.
        var candidates = serviceReturning(List.of(
                hit("v-change", 0.82),
                hit("v-release", 0.55),
                hit("v-deploy", 0.41))).selectStandards("a change procedure", 3);

        assertThat(selectedIds(candidates)).containsExactly("v-change");
    }

    @Test
    @DisplayName("genuine near-ties are both selected")
    void nearTiesAreBothSelected() {
        // 0.80 and 0.78 — the document plausibly falls under either, and a reviewer needs to see
        // both rather than have one silently chosen.
        var candidates = serviceReturning(List.of(
                hit("v-a", 0.80),
                hit("v-b", 0.78),
                hit("v-c", 0.50))).selectStandards("profile", 3);

        assertThat(selectedIds(candidates)).containsExactly("v-a", "v-b");
    }

    @Test
    @DisplayName("maxResults=1 yields one standard even on an exact tie")
    void limitOfOneWinsOverTies() {
        // How auto-selection calls this. Identical scores mean the ranking CANNOT separate the two,
        // which is a reason for a reviewer to choose — not a reason to review against both and report
        // the same gap twice under two different policy names.
        var candidates = serviceReturning(List.of(
                hit("v-a", 0.80),
                hit("v-b", 0.80),
                hit("v-c", 0.80))).selectStandards("profile", 1);

        assertThat(selectedIds(candidates)).containsExactly("v-a");
    }

    @Test
    @DisplayName("the standards a limit excluded are still reported, with a reason")
    void limitedOutCandidatesAreExplained() {
        // §15.1: the runner-up must remain visible, or a reviewer cannot tell that a close second
        // existed and choose it deliberately.
        var candidates = serviceReturning(List.of(
                hit("v-a", 0.80),
                hit("v-b", 0.80))).selectStandards("profile", 1);

        var runnerUp = candidates.stream()
                .filter(c -> "v-b".equals(c.standardVersionId()))
                .findFirst()
                .orElseThrow();

        assertThat(runnerUp.selected()).isFalse();
        assertThat(runnerUp.rejectionReason()).isNotBlank();
    }

    @Test
    @DisplayName("a rejected near-miss records why, rather than disappearing")
    void rejectionIsExplained() {
        // §15.1: a reviewer who expected a standard to be evaluated must be able to see that it was
        // considered and on what basis it was excluded.
        var candidates = serviceReturning(List.of(
                hit("v-best", 0.90),
                hit("v-near-miss", 0.60))).selectStandards("profile", 3);

        var rejected = candidates.stream()
                .filter(c -> "v-near-miss".equals(c.standardVersionId()))
                .findFirst()
                .orElseThrow();

        assertThat(rejected.selected()).isFalse();
        assertThat(rejected.rejectionReason()).contains("not comparable");
    }

    @Test
    @DisplayName("nothing above the relevance floor selects nothing")
    void belowFloorSelectsNothing() {
        // Comparing against the least-bad match would report a procedure's mismatches with an
        // unrelated policy as compliance gaps.
        var candidates = serviceReturning(List.of(
                hit("v-a", 0.20),
                hit("v-b", 0.19))).selectStandards("unrelated text", 3);

        assertThat(selectedIds(candidates)).isEmpty();
        assertThat(candidates).allSatisfy(c ->
                assertThat(c.rejectionReason()).contains("below threshold"));
    }

    @Test
    @DisplayName("the top hit is always selected, even at the floor")
    void topHitAtTheFloorIsStillSelected() {
        // It is a near-tie with itself, so the ratio must not exclude it.
        var candidates = serviceReturning(List.of(hit("v-only", 0.35)))
                .selectStandards("profile", 3);

        assertThat(selectedIds(candidates)).containsExactly("v-only");
    }

    @Test
    @DisplayName("no hits at all selects nothing rather than failing")
    void noHitsSelectsNothing() {
        var candidates = serviceReturning(List.of()).selectStandards("profile", 3);

        assertThat(candidates).isEmpty();
    }
}
