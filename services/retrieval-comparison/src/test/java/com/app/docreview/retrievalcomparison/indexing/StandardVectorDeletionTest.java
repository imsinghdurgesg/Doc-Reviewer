package com.app.docreview.retrievalcomparison.indexing;

import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standard vectors are deletable, and reindexing replaces rather than accumulates.
 * Architecture §8.1, §15.1, §19.2.
 *
 * <p><strong>Measured, not hypothetical.</strong> A live index held 37 standard-profile vectors against
 * 33 live versions, and a single candidate lookup returned fifteen near-identical copies of the same
 * standard plus one vector for a DRAFT version. Selection was choosing between duplicates of one
 * document instead of between genuinely different standards.
 *
 * <p>Two distinct defects are pinned here. Nothing deleted standard vectors at all, so removed versions
 * kept theirs; and {@code indexRequirements} upserted by requirement-document id, so re-extraction that
 * renumbered or reduced the requirement set left every previous vector in place under its old key. The
 * second is the one that silently corrupts retrieval measurement.
 */
class StandardVectorDeletionTest {

    /** Records what was upserted and deleted, in order. */
    private static final class RecordingVectorStore implements VectorStorePort {
        final List<String> deletions = new ArrayList<>();
        final List<String> upserts = new ArrayList<>();
        final List<Map<String, Object>> deleteFilters = new ArrayList<>();

        @Override
        public void ensureCollection(VectorCollection collection, int dimensions,
                                     String distanceMetric) {
        }

        @Override
        public void upsert(VectorCollection collection, String id, float[] vector,
                           Map<String, Object> payload) {
            upserts.add(collection.name() + ":" + id);
        }

        @Override
        public void upsertBatch(VectorCollection collection, List<VectorPoint> points) {
            points.forEach(p -> upserts.add(collection.name() + ":" + p.id()));
        }

        @Override
        public List<SearchHit> search(VectorCollection collection, float[] queryVector, int limit,
                                      Map<String, Object> filter, Double minScore) {
            return List.of();
        }

        @Override
        public void deleteByFilter(VectorCollection collection, Map<String, Object> filter) {
            deletions.add(collection.name() + ":" + filter);
            deleteFilters.add(filter);
        }
    }

    private static final EmbeddingGateway EMBEDDINGS = new EmbeddingGateway() {
        @Override
        public List<float[]> embed(List<String> inputs) {
            return inputs.stream().map(text -> new float[]{0.1f, 0.2f}).toList();
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

    private static VectorIndexingService service(RecordingVectorStore store) {
        return new VectorIndexingService(EMBEDDINGS, store);
    }

    @Nested
    @DisplayName("deleteStandardVectors")
    class Deletion {

        @Test
        @DisplayName("removes from BOTH the profile and requirement collections")
        void deletesBothCollections() {
            // The profile decides whether a standard is SELECTED; the requirements decide what is
            // compared once it is. Deleting only the profile would leave requirement vectors that can
            // still be retrieved as evidence for a standard nobody selected.
            var store = new RecordingVectorStore();

            service(store).deleteStandardVectors("v-1");

            assertThat(store.deletions).hasSize(2);
            assertThat(store.deletions.get(0)).contains("STANDARD_DOCUMENTS", "v-1");
            assertThat(store.deletions.get(1)).contains("STANDARD_REQUIREMENTS", "v-1");
        }

        @Test
        @DisplayName("scopes the delete by standardVersionId, never a bare collection wipe")
        void deleteIsScoped() {
            // An empty filter matches every point in Qdrant. Every delete issued here must name the
            // version, or a cleanup becomes a collection wipe.
            var store = new RecordingVectorStore();

            service(store).deleteStandardVectors("v-1");

            assertThat(store.deleteFilters).isNotEmpty();
            assertThat(store.deleteFilters)
                    .allSatisfy(filter -> assertThat(filter).containsEntry("standardVersionId", "v-1"));
        }

        @Test
        @DisplayName("a blank version id deletes NOTHING")
        void blankIdIsRefused() {
            // The dangerous case: a null or "" id would build an empty filter and wipe the collection.
            var store = new RecordingVectorStore();
            var indexing = service(store);

            indexing.deleteStandardVectors(null);
            indexing.deleteStandardVectors("");
            indexing.deleteStandardVectors("   ");

            assertThat(store.deletions)
                    .as("a blank id must never reach the vector store")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("reindexing is idempotent")
    class Idempotency {

        /**
         * A material requirement for version {@code v-1}.
         *
         * <p>Built through the real constructor rather than field setters: the document is deliberately
         * immutable (§25), and its point id is DERIVED as
         * {@code versionId:requirementSetVersion:requirementId}. Constructing it any other way would
         * test a different id scheme than production uses — and the id scheme is exactly what makes
         * stale vectors survive a reindex.
         */
        private StandardRequirementDocument requirement(String requirementId) {
            var extracted = new com.app.docreview.contracts.requirement.ExtractedRequirement(
                    requirementId,
                    null,
                    null,
                    "Change approval",
                    List.of("span-1"),
                    com.app.docreview.contracts.enums.NormativeStrength.MANDATORY,
                    "Changes must be approved before deployment.",
                    List.<com.app.docreview.contracts.requirement.AtomicClause>of(),
                    // Applicability: null is valid and means unconditional. Not the subject of this
                    // test, which is about vector lifecycle rather than requirement semantics.
                    null,
                    List.of("approval record"),
                    0.9);
            return new StandardRequirementDocument(
                    "std-1", "v-1", "rs-1", extracted, null, null, null);
        }

        @Test
        @DisplayName("requirement vectors are deleted before the new set is written")
        void deletesBeforeUpserting() {
            // THE defect. Points are keyed by requirement-document id, so re-extraction that renumbers
            // requirements upserts the new set while every old vector survives under its old key --
            // leaving two generations of one standard in the index, and letting evidence retrieval
            // cite a requirement that no longer exists.
            var store = new RecordingVectorStore();

            service(store).indexRequirements(
                    List.of(requirement("CHG-01")),
                    VectorIndexingService.StandardMetadata.empty());

            assertThat(store.deletions)
                    .as("stale vectors for this version must be cleared first")
                    .isNotEmpty();
            assertThat(store.deletions.getFirst())
                    .contains("STANDARD_REQUIREMENTS", "v-1");
            assertThat(store.upserts).isNotEmpty();
        }

        @Test
        @DisplayName("the delete is scoped to this version, not the whole collection")
        void reindexDeleteIsScoped() {
            // Reindexing one standard must not disturb any other standard's vectors.
            var store = new RecordingVectorStore();

            service(store).indexRequirements(
                    List.of(requirement("CHG-01")),
                    VectorIndexingService.StandardMetadata.empty());

            assertThat(store.deleteFilters)
                    .allSatisfy(filter -> assertThat(filter)
                            .containsEntry("standardVersionId", "v-1"));
        }

        @Test
        @DisplayName("indexing the same input twice yields the same point ids")
        void sameInputSamePoints() {
            // Reproducibility (§19.2): two identical runs must leave an identical index, so a
            // measurement taken after a reindex is comparable to one taken before it.
            var first = new RecordingVectorStore();
            var second = new RecordingVectorStore();
            var requirements = List.of(requirement("CHG-01"), requirement("CHG-02"));

            service(first).indexRequirements(
                    requirements, VectorIndexingService.StandardMetadata.empty());
            service(second).indexRequirements(
                    requirements, VectorIndexingService.StandardMetadata.empty());

            assertThat(first.upserts).isEqualTo(second.upserts);
        }

        @Test
        @DisplayName("an empty requirement set does not issue a delete")
        void emptySetDoesNothing() {
            // Returning early matters: a standard whose extraction produced nothing material must not
            // cause a delete that removes a previously-good index and leaves nothing in its place.
            var store = new RecordingVectorStore();

            service(store).indexRequirements(
                    List.of(), VectorIndexingService.StandardMetadata.empty());

            assertThat(store.deletions).isEmpty();
            assertThat(store.upserts).isEmpty();
        }
    }
}
