package com.app.docreview.retrievalcomparison.retrieval.requirement;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.contracts.retrieval.EvidenceCandidate;
import com.app.docreview.contracts.retrieval.RetrievalScores;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.retrieval.expansion.ContextExpander;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves uploaded-document evidence for one requirement. Architecture §15.2, §15.4.
 *
 * <p>Per requirement, not per document (§15.5): "never place a complete 300-page document and
 * multiple standards into one prompt". Requirement-level retrieval is what keeps each comparison
 * bounded and each finding attributable to specific passages.
 *
 * <p><strong>Coverage is recorded even when nothing is found</strong> — especially then. §15.4
 * requires it before a MISSING verdict, because "we searched 240 chunks across 9 sections and the
 * closest match scored 0.31" and "retrieval returned nothing because parsing failed" are opposite
 * situations that produce identical empty result lists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvidenceRetrievalService {

    /**
     * Passages per requirement.
     *
     * <p>Small on purpose. The model evaluates one requirement against these, and adding weak
     * candidates does not help it — it gives more surface for a spurious match, and §16.1's premise
     * is a focused judgement rather than a search over everything.
     */
    private static final int TOP_K = 6;

    /**
     * Minimum vector score.
     *
     * <p>0.25 cosine, not an RRF value. Applied to the raw semantic score before fusion so obvious
     * non-matches never enter the candidate set. Deliberately permissive: a false candidate costs
     * prompt tokens, a missed one costs a wrong verdict.
     */
    private static final double MIN_SEMANTIC_SCORE = 0.25;

    private final EmbeddingGateway embeddingGateway;
    private final VectorStorePort vectorStore;
    private final RequirementQueryBuilder queryBuilder;
    private final ScoreFusion scoreFusion;
    private final ContextExpander contextExpander;

    /**
     * @param allSpans the document's spans in reading order, for context expansion
     * @param parseComplete false when extraction was degraded; propagated into coverage so a scanned
     *                      page cannot be reported as an absent requirement
     */
    public Result retrieve(ExtractedRequirement requirement, String documentVersionId,
                           List<EvidenceSpan> allSpans, int totalChunks, boolean parseComplete,
                           List<String> unresolvedExternalReferences) {

        String query = queryBuilder.build(requirement);
        var queryVectors = embeddingGateway.embed(List.of(query));
        if (queryVectors.isEmpty()) {
            return new Result(List.of(), coverage(List.of(), 0, null, parseComplete,
                    unresolvedExternalReferences));
        }

        var hits = vectorStore.search(
                VectorCollection.PROCESS_CHUNKS, queryVectors.get(0), TOP_K,
                // Filtered server-side by document version: without this, a comparison could match a
                // passage from somebody else's document, which is both wrong and a data leak.
                Map.of("documentVersionId", documentVersionId),
                MIN_SEMANTIC_SCORE);

        // Semantic-only for now; the ranked list still goes through fusion so every result carries a
        // strategy version and the keyword component can be added without changing consumers.
        List<String> ranked = hits.stream().map(VectorStorePort.SearchHit::id).toList();
        Map<String, Double> semanticScores = new java.util.HashMap<>();
        hits.forEach(h -> semanticScores.put(h.id(), h.score()));
        var fused = scoreFusion.fuse(ranked, List.of(), semanticScores, Map.of());

        List<EvidenceCandidate> candidates = new ArrayList<>();
        Set<String> sectionsSearched = new LinkedHashSet<>();

        for (var hit : hits) {
            var payload = hit.payload();
            @SuppressWarnings("unchecked")
            List<String> spanIds = payload.get("spanIds") instanceof List<?> raw
                    ? raw.stream().map(String::valueOf).toList() : List.of();
            @SuppressWarnings("unchecked")
            List<String> sectionPath = payload.get("sectionPath") instanceof List<?> raw
                    ? raw.stream().map(String::valueOf).toList() : List.of();
            sectionsSearched.addAll(sectionPath);

            RetrievalScores scores = fused.get(hit.id());
            candidates.add(new EvidenceCandidate(
                    String.valueOf(payload.getOrDefault("chunkId", hit.id())),
                    documentVersionId,
                    spanIds,
                    sectionPath,
                    // Text is NOT taken from the payload: spans are the source of truth for
                    // displayed evidence (§11.3), and duplicating text into the index would create
                    // a second copy that can drift from the span it came from.
                    null,
                    asInteger(payload.get("pageStart")),
                    asInteger(payload.get("pageEnd")),
                    scores,
                    contextExpander.expand(allSpans, spanIds)));
        }

        Double closest = hits.isEmpty() ? null : hits.get(0).score();
        var coverage = coverage(List.copyOf(sectionsSearched), totalChunks, closest,
                parseComplete, unresolvedExternalReferences);

        log.debug("Requirement {}: {} candidate(s), closest score {}",
                requirement.requirementId(), candidates.size(), closest);
        return new Result(candidates, coverage);
    }

    private SearchCoverage coverage(List<String> sections, int chunksSearched, Double closest,
                                    boolean parseComplete, List<String> unresolved) {
        return new SearchCoverage(
                sections, chunksSearched, MIN_SEMANTIC_SCORE, closest,
                List.of(), unresolved == null ? List.of() : unresolved,
                parseComplete,
                // Whether the document claims to cover this domain is a §15.4 signal that needs the
                // document profile; not yet wired, and defaulting to false is the conservative
                // choice — it makes an absence verdict harder to justify, not easier.
                false);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    /**
     * @param coverage always present, including when {@code candidates} is empty — that is the case
     *                 §15.4 exists for
     */
    public record Result(List<EvidenceCandidate> candidates, SearchCoverage coverage) {}
}
