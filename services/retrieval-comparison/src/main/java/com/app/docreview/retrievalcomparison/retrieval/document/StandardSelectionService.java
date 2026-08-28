package com.app.docreview.retrievalcomparison.retrieval.document;

import com.app.docreview.contracts.retrieval.StandardCandidate;
import com.app.docreview.retrievalcomparison.llm.DocumentContextModelGateway;
import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import com.app.docreview.retrievalcomparison.vector.VectorCollection;
import com.app.docreview.retrievalcomparison.vector.VectorStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects which standards to compare against. Architecture §15.1.
 *
 * <p>Returns rejected candidates alongside selected ones, with the reason. That is not politeness:
 * a reviewer who expected a standard to be evaluated needs to see that it scored 0.21 and was cut,
 * rather than wondering whether it exists. It is also the only way §17.6's distinction survives — a
 * requirement from an unselected standard was never tested, so its absence from the findings is not
 * evidence of compliance.
 *
 * <p><strong>A retrieval score is relevance, not compliance</strong> (§15.1). 0.91 means "this
 * standard is probably about the same subject", never "the document mostly complies".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardSelectionService {

    /** Candidates considered before thresholding. */
    private static final int SEARCH_LIMIT = 20;

    /**
     * Minimum raw cosine score for a standard to be offered.
     *
     * <p>Higher than the evidence threshold: comparing against an irrelevant standard produces dozens
     * of spurious MISSING findings, which is far more damaging than one weak evidence candidate. The
     * user can still select a standard manually (§4), so a strict cut here loses nothing recoverable.
     */
    private static final double MIN_RELEVANCE = 0.35;

    /**
     * How close a further standard must score to the best one to also be selected.
     *
     * <p><strong>Clearing the relevance floor is not enough.</strong> An organization typically has
     * several standards on one subject — a change-management policy, a release policy, a deployment
     * standard — and all of them score respectably against a change procedure. Selecting every one
     * that beats the floor measured a single document against three standards at once: 118 findings
     * instead of 65, with the same control id appearing three times from three different sources. A
     * reviewer reads that as duplication, and cannot tell which standard the document is actually
     * governed by.
     *
     * <p>So a second standard is only added when it is a genuine near-tie with the first. If one
     * standard is clearly the most relevant, it is compared alone — which is what a reviewer expects
     * when they upload a procedure and ask what governs it.
     */
    private static final double NEAR_TIE_RATIO = 0.92;

    private final EmbeddingGateway embeddingGateway;
    private final VectorStorePort vectorStore;
    private final ScoreFusion scoreFusion;

    /**
     * @param documentProfile the uploaded document's profile text (§15.1) — title, purpose, scope,
     *                        major headings. NOT the full document: a vector averaged over 300 pages
     *                        sits near the centroid of all documents and discriminates nothing.
     */
    public List<StandardCandidate> selectStandards(String documentProfile, int maxResults) {
        return selectStandards(documentProfile, maxResults, AssessmentContext.none());
    }

    /**
     * Candidates ranked with the assessment context applied. Architecture §15.1.
     *
     * <p>Context REORDERS; it never excludes. A department match promotes a candidate of similar
     * relevance above one without; a mismatch demotes it. Neither can promote an irrelevant standard
     * above a relevant one, because the weight is bounded — see {@link AssessmentContext#weight}.
     *
     * <p><strong>The near-tie gate does not apply when a context is supplied.</strong> That gate exists
     * to stop a second standard being auto-selected on a marginal score, which is right for automatic
     * selection of one. Here the reviewer is choosing from a list, so a standard at 80% of the best is
     * information rather than noise — withholding it would make the selection page less useful than
     * the automatic path it replaces.
     */
    public List<StandardCandidate> selectStandards(String documentProfile, int maxResults,
                                                   AssessmentContext context) {
        var hits = retrieve(documentProfile);
        return rank(hits, maxResults, context);
    }

    /**
     * The raw vector hits for a profile, before any ranking is applied.
     *
     * <p>Separated from {@link #rank} so a caller can look at what came back, derive a context from it,
     * and rank ONCE with that context — rather than embedding and searching a second time. Document
     * context inference needs exactly this: it must know which labels exist on the candidates before it
     * can ask a model to choose among them.
     */
    public List<VectorStorePort.SearchHit> retrieve(String documentProfile) {
        var queryVectors = embeddingGateway.embed(List.of(documentProfile));
        if (queryVectors.isEmpty()) {
            return List.of();
        }
        return vectorStore.search(VectorCollection.STANDARD_DOCUMENTS, queryVectors.get(0),
                SEARCH_LIMIT, Map.of(), null);
    }

    /**
     * Scores, thresholds, and labels a set of hits under a context.
     *
     * <p>Pure with respect to retrieval: the same hits ranked under two different contexts differ only
     * in order and selection, which is what makes it safe to retrieve once and rank once afterwards.
     */
    public List<StandardCandidate> rank(List<VectorStorePort.SearchHit> hits, int maxResults,
                                        AssessmentContext context) {
        List<String> ranked = hits.stream().map(VectorStorePort.SearchHit::id).toList();
        Map<String, Double> semanticScores = new HashMap<>();
        hits.forEach(h -> semanticScores.put(h.id(), h.score()));
        var fused = scoreFusion.fuse(ranked, List.of(), semanticScores, Map.of());

        // ORDERED BY WEIGHTED SCORE, not raw.
        //
        // The weight previously affected only the threshold test, so a context could DROP a candidate
        // but never promote one: `maxResults` and the near-tie bar were both applied in raw order, and
        // a boosted standard sitting fourth stayed fourth. "Metadata reorders candidates of comparable
        // relevance" was the stated intent and was not what the code did.
        //
        // Sorting is what makes the bounded weight meaningful. The bound still holds — a 0.20 gap in
        // raw relevance cannot be closed by ±18%/−14% — so this promotes among comparable candidates
        // without letting labels override relevance.
        var ordered = hits.stream()
                .sorted(java.util.Comparator
                        .comparingDouble((VectorStorePort.SearchHit h) -> weightedScore(h, context))
                        .reversed())
                .toList();

        // One default version per standard, decided before scoring so the choice cannot depend on
        // which version happened to score higher.
        var defaultVersionIds = defaultVersionIds(ordered);

        List<StandardCandidate> candidates = new ArrayList<>();
        int selectedCount = 0;

        // The best WEIGHTED score sets the bar, and `ordered` puts it first. Taking it from the raw
        // order would compare every candidate against a standard that is no longer top-ranked.
        double bestScore = ordered.isEmpty() ? 0.0 : weightedScore(ordered.getFirst(), context);

        for (var hit : ordered) {
            var payload = hit.payload();
            // Thresholded on the RAW semantic score, not the fused one: an RRF value is an ordering
            // signal with no absolute meaning (top result ≈ 0.0164), so a threshold against it would
            // be arbitrary. See ScoreFusion's note.
            // Weighted by the context: metadata reorders candidates of comparable relevance without
            // being able to override relevance itself.
            double weighted = weightedScore(hit, context);

            boolean aboveThreshold = weighted >= MIN_RELEVANCE;
            boolean withinLimit = selectedCount < maxResults;
            // Only the DEFAULT version of a standard is preselected (§8.1).
            //
            // The corpus holds three versions of "Change Management Standard" (v2, v3, vv1), all
            // ACTIVE and all scoring similarly. Preselecting each one measures the document against
            // the same standard three times and reports every gap three times under three version
            // labels — which reads as three distinct problems.
            //
            // The default is chosen by LIFECYCLE, never by comparing version labels: "vv1" sorts
            // before "v2" lexically, and the labels are administrator-entered free text with no
            // ordering guarantee. See StandardVersionPreference.
            boolean isDefaultVersion = defaultVersionIds.contains(hit.id());
            // The top hit is always a near-tie with itself, so this admits it unconditionally.
            // Bypassed under a context: a reviewer picking from a list benefits from seeing a
            // lower-ranked candidate, whereas automatic selection of one must not drift onto it.
            //
            // Compared weighted-to-weighted. With no context every weight is 1.0, so this is
            // identical to the previous raw comparison — the automatic single-standard path is
            // unchanged.
            boolean nearTie = context.isEmpty()
                    ? weighted >= bestScore * NEAR_TIE_RATIO
                    : true;
            boolean selected = aboveThreshold && withinLimit && nearTie && isDefaultVersion;

            String rejection = null;
            if (!aboveThreshold) {
                rejection = "relevance %.3f below threshold %.2f".formatted(weighted, MIN_RELEVANCE);
            } else if (!isDefaultVersion) {
                // Named explicitly so a reviewer can see this is a VERSION decision, not a relevance
                // one — and can select the other version deliberately if they know it applies.
                String lifecycle = asString(payload.get("lifecycle"));
                String version = asString(payload.get("version"));
                rejection = StandardVersionPreference.isSuperseded(lifecycle)
                        ? "superseded version %s (%s)".formatted(
                                version == null ? "?" : version, lifecycle)
                        : "not the default version for this standard (%s)".formatted(
                                version == null ? "?" : version);
            } else if (!nearTie) {
                // Recorded rather than silently dropped: §15.1 requires a reviewer to be able to see
                // that a standard was considered and why it was not used.
                rejection = "relevance %.3f is not comparable to the best match %.3f"
                        .formatted(weighted, bestScore);
            } else if (!withinLimit) {
                rejection = "beyond the top %d selected standards".formatted(maxResults);
            }
            if (selected) {
                selectedCount++;
            }

            @SuppressWarnings("unchecked")
            List<String> tags = payload.get("tags") instanceof List<?> raw
                    ? raw.stream().map(String::valueOf).toList() : List.of();

            candidates.add(new StandardCandidate(
                    String.valueOf(payload.get("standardId")),
                    String.valueOf(payload.get("standardVersionId")),
                    String.valueOf(payload.getOrDefault("standardKey", "")),
                    String.valueOf(payload.getOrDefault("title", "")),
                    String.valueOf(payload.getOrDefault("owner", "")),
                    asString(payload.get("department")),
                    asString(payload.get("standardFamily")),
                    asString(payload.get("documentType")),
                    asString(payload.get("version")),
                    asString(payload.get("lifecycle")),
                    tags,
                    fused.get(hit.id()),
                    selected,
                    rejection));
        }

        log.info("Standard selection: {} candidate(s), {} selected (threshold {})",
                candidates.size(), selectedCount, MIN_RELEVANCE);
        return candidates;
    }

    /**
     * The point id of the default version for each standard. Architecture §8.1, §15.1.
     *
     * <p>Grouped by {@code standardKey} — the stable business key shared across versions — rather than
     * by title, which changes between versions, or by {@code standardId}, which is per-standard but
     * absent on older payloads.
     *
     * <p>A hit with NO {@code standardKey} is treated as its own group and always allowed as a default.
     * Suppressing it would hide every pre-Phase-1 vector from selection, which is a far worse failure
     * than occasionally offering two versions of one standard.
     *
     * <p>When no version of a standard is eligible to be a default (all DRAFT or all ARCHIVED), the
     * best-ranked one is still offered. §15.1 forbids withholding a standard entirely; the rejection
     * reason carries the lifecycle so the reviewer sees what they are choosing.
     */
    private Set<String> defaultVersionIds(List<VectorStorePort.SearchHit> hits) {
        Map<String, List<VectorStorePort.SearchHit>> byKey = new java.util.LinkedHashMap<>();
        Set<String> defaults = new java.util.LinkedHashSet<>();

        for (var hit : hits) {
            String key = asString(hit.payload().get("standardKey"));
            if (key == null || key.isBlank()) {
                // Ungrouped: always eligible. See the note above on legacy payloads.
                defaults.add(hit.id());
                continue;
            }
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
        }

        for (var group : byKey.values()) {
            var eligible = group.stream()
                    .filter(h -> StandardVersionPreference.canBeDefault(
                            asString(h.payload().get("lifecycle"))))
                    .toList();
            // Fall back to the whole group when none is eligible, rather than offering nothing.
            var pool = eligible.isEmpty() ? group : eligible;

            pool.stream()
                    .min(StandardVersionPreference.preferring(
                            h -> asString(h.payload().get("lifecycle")),
                            StandardSelectionService::indexedAtMillis))
                    .ifPresent(best -> defaults.add(best.id()));
        }
        return defaults;
    }

    /**
     * A hit's index time in epoch millis, or 0 when unrecorded.
     *
     * <p>Used only to break a tie between two versions in the SAME lifecycle. 0 for a missing value
     * makes such a version lose the tie-break, which is right: a version with no recorded index time
     * should not outrank one that has one.
     */
    private static long indexedAtMillis(VectorStorePort.SearchHit hit) {
        var raw = hit.payload().get("indexedAt");
        if (raw == null) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(String.valueOf(raw)).toEpochMilli();
        } catch (Exception e) {
            // A malformed timestamp must not break selection for every standard in the corpus.
            return 0L;
        }
    }

    /** A hit's relevance after the context's bounded adjustment. */
    private static double weightedScore(VectorStorePort.SearchHit hit, AssessmentContext context) {
        var payload = hit.payload();
        return hit.score() * context.weight(
                asString(payload.get("department")),
                asString(payload.get("standardFamily")),
                asString(payload.get("documentType")));
    }

    /**
     * The department, family, and documentType labels present on these hits. Architecture §15.1.
     *
     * <p>This is the vocabulary a model may choose from when inferring what a document is. It comes
     * from the retrieved candidates rather than the whole corpus for two reasons: the labels that
     * matter are the ones on standards this document could plausibly match, and enumerating distinct
     * payload values across the entire collection would be a scan on every lookup.
     *
     * <p>Lives here because the payload keys are this class's knowledge. A caller assembling the same
     * lists would duplicate them, and the two copies would drift the next time a key is renamed.
     */
    public DocumentContextModelGateway.Vocabulary vocabularyOf(
            List<VectorStorePort.SearchHit> hits) {
        return new DocumentContextModelGateway.Vocabulary(
                distinctPayloadValues(hits, "department"),
                distinctPayloadValues(hits, "standardFamily"),
                distinctPayloadValues(hits, "documentType"));
    }

    private static List<String> distinctPayloadValues(List<VectorStorePort.SearchHit> hits,
                                                      String key) {
        // Distinctness and bounding are the Vocabulary record's job; this only extracts.
        return hits.stream()
                .map(hit -> asString(hit.payload().get(key)))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    /**
     * A nullable payload string.
     *
     * <p>{@code String.valueOf(null)} yields the literal "null", which would then be COMPARED against
     * a context value and count as a mismatch — penalising a standard for having no department rather
     * than for having the wrong one.
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
