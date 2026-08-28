package com.app.docreview.retrievalcomparison.indexing;

import com.app.docreview.contracts.document.ChunkType;
import com.app.docreview.contracts.document.ChunkTypeClassifier;
import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.retrieval.IndexCompatibility;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes embeddings into the three Qdrant collections. Architecture §13.2, Phase 5.
 *
 * <p>Every write records the embedding model alias and dimensions in the payload (§13.2). That is not
 * bookkeeping: it is how a later reader detects that a collection contains vectors from two different
 * models, which is otherwise undetectable — cosine similarity across model spaces is arithmetically
 * valid and semantically meaningless, so retrieval keeps working and starts returning nonsense.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorIndexingService {

    public static final String INDEX_SCHEMA_VERSION = "v1";
    private static final String DISTANCE_METRIC = "cosine";

    private final EmbeddingGateway embeddingGateway;
    private final VectorStorePort vectorStore;

    /**
     * Indexes a standard's document-level profile (§15.1).
     *
     * <p>One vector per standard VERSION, built from title, description, and metadata — not from the
     * full text. The question this collection answers is "which standard is relevant", and a vector
     * averaged over 40 pages is closer to the mean of all standards than to any one of them.
     */
    /**
     * Metadata inherited by every chunk of a standard. Architecture §15.1.
     *
     * <p>A record rather than six more parameters: they travel together, and a long positional call is
     * where a caller silently swaps category for family. Every field is optional — a standard indexed
     * before these existed still works, just without the ranking signals.
     */
    public record StandardMetadata(
            String department,
            String category,
            String standardFamily,
            String documentType,
            List<String> tags,
            /**
             * The stable business key shared by every version of one standard, e.g. {@code GRC-CHG-2026}.
             *
             * <p>Required for grouping candidates by STANDARD rather than by version. Without it the
             * only way to tell that three hits are three versions of one document is to compare titles,
             * which breaks the moment a title is edited between versions.
             */
            String standardKey,
            /** The version label as an administrator entered it. Never parsed for ordering (§8.1). */
            String version,
            /**
             * Lifecycle at index time: DRAFT, ACTIVE, DEPRECATED, or ARCHIVED.
             *
             * <p>This — not a lexical comparison of version strings — is how the default version for a
             * standard is chosen. "v10" sorts before "v9", "vv1" before "v2", and "2.4" before "2.5"
             * only by luck; lifecycle states what the organization actually considers current.
             */
            String lifecycle) {

        public static StandardMetadata empty() {
            return new StandardMetadata(null, null, null, null, List.of(), null, null, null);
        }

        /** The pre-Phase-1 shape, for callers that have no key/version/lifecycle to offer. */
        public static StandardMetadata of(String department, String category, String standardFamily,
                                          String documentType, List<String> tags) {
            return new StandardMetadata(department, category, standardFamily, documentType, tags,
                    null, null, null);
        }

        /**
         * Copies the metadata onto a chunk payload.
         *
         * <p>INHERITED rather than joined at query time: Qdrant can only rank on what is in the
         * payload, and a lookup back to Mongo per candidate would defeat the point of an index.
         */
        void applyTo(java.util.Map<String, Object> payload) {
            payload.put("department", department);
            payload.put("category", category);
            payload.put("standardFamily", standardFamily);
            payload.put("documentType", documentType);
            payload.put("tags", tags == null ? List.of() : tags);
            // Grouping and version-selection signals. Written even when null so a re-embed always
            // overwrites a stale value rather than leaving the previous one behind.
            payload.put("standardKey", standardKey);
            payload.put("version", version);
            payload.put("lifecycle", lifecycle);
        }
    }

    public void indexStandardProfile(String standardId, String standardVersionId,
                                     String title, String description, String owner,
                                     String businessUnit, String category, List<String> tags) {
        indexStandardProfile(standardId, standardVersionId, title, description, owner,
                StandardMetadata.of(businessUnit, category, null, null, tags));
    }

    /** @param metadata inherited onto the profile payload, so candidates can be ranked by it */
    public void indexStandardProfile(String standardId, String standardVersionId,
                                     String title, String description, String owner,
                                     StandardMetadata metadata) {
        String businessUnit = metadata.department();
        String category = metadata.category();
        List<String> tags = metadata.tags();

        String profile = String.join(". ",
                title == null ? "" : title,
                description == null ? "" : description,
                category == null ? "" : category,
                // Family and document type are in the VECTOR as well as the payload: they describe
                // what the standard is about, so they help similarity, not only filtering.
                metadata.standardFamily() == null ? "" : metadata.standardFamily(),
                metadata.documentType() == null ? "" : metadata.documentType(),
                tags == null ? "" : String.join(" ", tags));

        var vectors = embeddingGateway.embed(List.of(profile));
        if (vectors.isEmpty()) {
            log.warn("No embedding produced for standard profile {}", standardVersionId);
            return;
        }

        ensure(VectorCollection.STANDARD_DOCUMENTS);

        Map<String, Object> payload = new HashMap<>(compatibilityPayload());
        payload.put("standardId", standardId);
        payload.put("standardVersionId", standardVersionId);
        payload.put("title", title);
        payload.put("owner", owner);
        payload.put("businessUnit", businessUnit);
        // Breaks a tie between two versions in the SAME lifecycle. Written here rather than derived,
        // because the version's own createdAt says when the document was published, not when this
        // vector was built — and it is the vector's recency that matters for choosing between two
        // equally-current versions.
        payload.put("indexedAt", Instant.now().toString());
        metadata.applyTo(payload);

        vectorStore.upsert(VectorCollection.STANDARD_DOCUMENTS,
                standardVersionId, vectors.get(0), payload);
        log.info("Indexed standard profile {}", standardVersionId);
    }

    /**
     * Indexes each requirement of a published requirement set (§15.2).
     *
     * <p>The embedded text is the normalized requirement plus its evidence hints, because a document
     * satisfying a requirement rarely uses the standard's wording. Only material requirements are
     * indexed: an advisory requirement is never compared, so a vector for it is search-space with no
     * possible finding.
     */
    public void indexRequirements(List<StandardRequirementDocument> requirements) {
        indexRequirements(requirements, StandardMetadata.empty());
    }

    /**
     * @param metadata inherited onto every requirement chunk. Without it a control retrieved directly
     *                 carries no department or family, so control-level retrieval cannot be ranked by
     *                 the same signals as document-level retrieval — and the two would disagree.
     */
    public void indexRequirements(List<StandardRequirementDocument> requirements,
                                  StandardMetadata metadata) {
        var material = requirements.stream()
                .filter(StandardRequirementDocument::isMaterial)
                .toList();
        if (material.isEmpty()) {
            return;
        }

        ensure(VectorCollection.STANDARD_REQUIREMENTS);

        // REPLACE, don't merge. Points are keyed by the requirement's document id, so re-extraction
        // that renumbers requirements — or extracts fewer of them — upserts the new set while every
        // vector from the previous run survives under its old key. The index would then hold two
        // generations of the same standard, and evidence retrieval could cite a requirement that no
        // longer exists in the requirement set.
        //
        // Deleting this version's requirement vectors first makes reindexing idempotent: the same
        // input twice yields the same index, which is what §19.2 reproducibility needs. Scoped by
        // standardVersionId, so it cannot touch another standard.
        String versionId = material.getFirst().getStandardVersionId();
        if (versionId != null && !versionId.isBlank()) {
            vectorStore.deleteByFilter(VectorCollection.STANDARD_REQUIREMENTS,
                    Map.of("standardVersionId", versionId));
        }

        List<String> texts = material.stream()
                .map(r -> String.join(". ", r.getNormalizedRequirement(),
                        String.join(" ", r.getExpectedEvidenceHints())))
                .toList();
        var vectors = embeddingGateway.embed(texts);

        List<VectorStorePort.VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < material.size(); i++) {
            var r = material.get(i);
            Map<String, Object> payload = new HashMap<>(compatibilityPayload());
            payload.put("standardId", r.getStandardId());
            payload.put("standardVersionId", r.getStandardVersionId());
            payload.put("requirementSetVersion", r.getRequirementSetVersion());
            payload.put("requirementId", r.getRequirementId());
            payload.put("parentControlId", r.getParentControlId());
            payload.put("title", r.getTitle());
            // Span IDs travel in the payload so a hit immediately names what the model may cite.
            payload.put("sourceSpanIds", r.getSourceSpanIds());
            // Always REQUIREMENT: these points come from the extracted requirement set, where being an
            // obligation is the entry criterion. The enum rather than a literal so a taxonomy rename
            // breaks the build instead of silently orphaning every requirement vector from its type.
            payload.put("chunkType", ChunkType.REQUIREMENT.name());
            payload.put("chunkTaxonomyVersion", ChunkTypeClassifier.TAXONOMY_VERSION);
            metadata.applyTo(payload);
            payload.put("normativeStrength", r.getNormativeStrength().name());
            payload.put("severity", r.getSeverity() == null ? null : r.getSeverity().name());

            points.add(new VectorStorePort.VectorPoint(r.getId(), vectors.get(i), payload));
        }

        vectorStore.upsertBatch(VectorCollection.STANDARD_REQUIREMENTS, points);
        log.info("Indexed {} material requirement(s) for {}",
                points.size(), material.get(0).getStandardVersionId());
    }

    /**
     * Indexes the uploaded document's chunks (§15.2).
     *
     * <p>{@code retentionExpiresAt} is written so §13.2's retention sweep can delete these by filter.
     * Process vectors outlive nothing: once the document is gone, a comparison must not be able to
     * silently re-run against its residue.
     */
    public void indexProcessChunks(String documentVersionId, String comparisonId,
                                   List<SemanticChunk> chunks, Instant retentionExpiresAt) {
        if (chunks.isEmpty()) {
            return;
        }

        ensure(VectorCollection.PROCESS_CHUNKS);

        var vectors = embeddingGateway.embed(chunks.stream().map(SemanticChunk::text).toList());

        List<VectorStorePort.VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            Map<String, Object> payload = new HashMap<>(compatibilityPayload());
            payload.put("documentVersionId", documentVersionId);
            payload.put("comparisonId", comparisonId);
            payload.put("chunkId", chunk.chunkId());
            payload.put("sectionPath", chunk.sectionPath());
            payload.put("spanIds", chunk.spanIds());
            payload.put("pageStart", chunk.pageStart());
            payload.put("pageEnd", chunk.pageEnd());
            // Stored VERBATIM as the chunker assigned it, plus a normalised form alongside.
            //
            // Writing only the normalised value would rewrite history: a V1 CONTROL_SECTION chunk
            // would be recorded as UNCLASSIFIED_LEGACY and its original label lost, so the index could
            // no longer show what the V1 chunker actually decided. Keeping both means the raw value
            // stays auditable while ranking has something well-defined to read.
            payload.put("chunkType", chunk.chunkType());
            payload.put("chunkTypeNormalised", ChunkType.parse(chunk.chunkType()).name());
            payload.put("chunkTaxonomyVersion", ChunkTypeClassifier.TAXONOMY_VERSION);
            payload.put("chunkingStrategyVersion", chunk.chunkingStrategyVersion());
            payload.put("retentionExpiresAt",
                    retentionExpiresAt == null ? null : retentionExpiresAt.toString());

            points.add(new VectorStorePort.VectorPoint(chunk.chunkId(), vectors.get(i), payload));
        }

        vectorStore.upsertBatch(VectorCollection.PROCESS_CHUNKS, points);
        log.info("Indexed {} process chunk(s) for {}", points.size(), documentVersionId);
    }

    /** Deletes a document's vectors when it expires or is deleted (§13.2). */
    public void deleteProcessVectors(String documentVersionId) {
        vectorStore.deleteByFilter(VectorCollection.PROCESS_CHUNKS,
                Map.of("documentVersionId", documentVersionId));
    }

    /**
     * Deletes every vector belonging to one standard version. Architecture §8.1, §15.1.
     *
     * <p><strong>Why this is required, not housekeeping.</strong> Without it a deleted or superseded
     * standard version keeps its profile vector, and that vector stays searchable — so it is returned
     * as a CANDIDATE for comparison. A measured example: a live index held 37 profile vectors against
     * 33 live versions, and a single candidate lookup returned fifteen near-identical copies of the same
     * standard. Selection then picks between duplicates of one document instead of between genuinely
     * different standards, and a reviewer cannot tell which version they are comparing against.
     *
     * <p>It also invalidates retrieval measurement: recall against a corpus containing phantom
     * standards is not a measurement of anything reproducible.
     *
     * <p><strong>Both collections.</strong> The profile decides whether a standard is selected; the
     * requirements decide what is compared once it is. Deleting only the profile would leave orphaned
     * requirement vectors that could still be retrieved as evidence for a standard nobody selected.
     */
    public void deleteStandardVectors(String standardVersionId) {
        if (standardVersionId == null || standardVersionId.isBlank()) {
            // Never delete by a blank filter. On Qdrant an empty match would delete the COLLECTION's
            // contents rather than one standard's, which is the worst possible outcome of a cleanup.
            log.warn("Refusing to delete standard vectors for a blank version id");
            return;
        }

        vectorStore.deleteByFilter(VectorCollection.STANDARD_DOCUMENTS,
                Map.of("standardVersionId", standardVersionId));
        vectorStore.deleteByFilter(VectorCollection.STANDARD_REQUIREMENTS,
                Map.of("standardVersionId", standardVersionId));
        log.info("Deleted standard vectors for version {}", standardVersionId);
    }

    public IndexCompatibility compatibility(String chunkingStrategyVersion) {
        return new IndexCompatibility(
                embeddingGateway.modelAlias(), embeddingGateway.dimensions(),
                DISTANCE_METRIC, chunkingStrategyVersion, INDEX_SCHEMA_VERSION, Instant.now());
    }

    private void ensure(VectorCollection collection) {
        vectorStore.ensureCollection(collection, embeddingGateway.dimensions(), DISTANCE_METRIC);
    }

    /** Stamped on every point so incompatible vectors are detectable after the fact (§13.4). */
    private Map<String, Object> compatibilityPayload() {
        return Map.of(
                "embeddingModel", embeddingGateway.modelAlias(),
                "embeddingDimensions", embeddingGateway.dimensions(),
                "indexVersion", INDEX_SCHEMA_VERSION);
    }
}
