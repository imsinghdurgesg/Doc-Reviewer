package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.comparison.ApplicabilityDecision;
import com.app.docreview.contracts.document.ChunkType;
import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one requirement's applicability decision. Architecture §12.5, §17.6, §19.2.
 *
 * <p><strong>Why a coordinator separate from the resolver.</strong> The resolver is deterministic and
 * holds no collaborators — no model, no vector store, no network — which is what makes its verdict
 * reproducible and free. But deciding applicability in a real review needs I/O: loading the requirement,
 * reading the confirmed context, and fetching SCOPE and APPLICABILITY chunks. Putting that in the
 * resolver would give it dependencies and make the "costs nothing" property untestable.
 *
 * <p>So this class does the loading and persisting; the resolver does the deciding. The split is the
 * point: everything here is replaceable I/O, and the judgement it wraps is not.
 *
 * <p><strong>Scope chunks are fetched only when the resolver asks.</strong> {@code needsScopeContext} is
 * true for the minority of requirements that state a real condition. Fetching them for every requirement
 * would add a read per requirement across the whole review and invite interpretation of conditions the
 * standard never wrote.
 *
 * <p><strong>Nothing is interpreted by a model here (Phase 2 bound).</strong> When no structured rule can
 * be evaluated, the outcome is {@link ApplicabilityStatus#UNCERTAIN} with the scope chunks attached as
 * reviewer evidence. Reading free-text scope with a new model call is explicitly out of scope for this
 * phase — the platform states what it could not decide and hands over what it read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequirementApplicabilityCoordinator {

    /**
     * Scope chunks attached to one decision.
     *
     * <p>Bounded because these are reviewer evidence, not a corpus dump: a decision carrying forty
     * chunks is one nobody reads, and the span list is persisted per requirement.
     */
    private static final int MAX_SCOPE_CHUNKS = 5;

    /** The chunk types that bear on whether a requirement applies (§11.4). */
    private static final List<ChunkType> SCOPE_TYPES =
            List.of(ChunkType.SCOPE, ChunkType.APPLICABILITY);

    private final ApplicabilityResolver resolver;
    private final EffectiveConditionResolver effectiveConditions;
    private final EvidenceSpanReader spanReader;
    private final CoverageLedgerService ledgerService;

    /**
     * Decides applicability for one requirement and records it.
     *
     * @param requirement      the loaded requirement
     * @param confirmedContext what the reviewer has CONFIRMED, never what was merely inferred
     * @return the decision, ready to persist. Never null.
     */
    public ApplicabilityDecision decide(StandardRequirementDocument requirement,
                                        ApplicabilityResolver.ConfirmedContext confirmedContext) {

        var applicability = requirement.toExtractedRequirement().applicability();

        // THE GOVERNED PATH (§12.5, §19.2).
        //
        // Human review decisions are applied as a read-time overlay: the extracted requirement set stays
        // immutable, so the model's original classification remains inspectable next to the reviewer's.
        // That is what makes correcting a decision cheap — no re-extraction, and no risk of a later
        // re-extraction silently discarding a review.
        //
        // The decisive effect is that an unreviewed prose SCOPE_GATE can no longer exclude anything.
        // Review of 115 such predictions reclassified 113, so trusting them to remove controls from a
        // review would have been wrong 98% of the time.
        var effective = effectiveConditions.resolve(
                requirement.getStandardVersionId(),
                requirement.getRequirementSetVersion(),
                requirement.getRequirementId(),
                applicability == null ? List.of() : applicability.effectiveConditions());
        var decision = resolver.resolveGoverned(effective, confirmedContext);

        // EVIDENCE IN TWO LAYERS (§16.5).
        //
        // Layer 1 — the condition's own source span, from the resolver. Always available, because every
        // extracted condition came from some requirement text. This is what stops an applicability
        // decision being evidence-free: previously an UNCERTAIN decision on a standard with no
        // SCOPE-typed chunks arrived with zero spans, giving the reviewer a conclusion and nothing to
        // check it against.
        //
        // Layer 2 — dedicated SCOPE and APPLICABILITY passages, when the standard has them. Additional
        // interpretive context, not a precondition: a condition embedded directly in a requirement
        // legitimately has only its own span.
        List<String> evidenceSpanIds = new ArrayList<>(decision.conditionSourceSpanIds());
        if (decision.needsScopeContext()) {
            for (String scopeSpan : scopeSpanIdsFor(requirement.getStandardVersionId())) {
                if (!evidenceSpanIds.contains(scopeSpan)) {
                    evidenceSpanIds.add(scopeSpan);
                }
            }
        }

        var result = new ApplicabilityDecision(
                requirement.getRequirementId(),
                decision.status(),
                decision.reason(),
                List.copyOf(evidenceSpanIds),
                decision.missingContextFields(),
                decision.conditions(),
                decidedBy(effective));

        // Counts and status only — never the condition text, which is standard content (§35).
        if (result.status() != ApplicabilityStatus.APPLICABLE) {
            log.info("Requirement {} applicability {} ({} scope span(s), {} missing field(s))",
                    requirement.getRequirementId(), result.status(),
                    result.scopeSpanIds().size(), result.missingContextFields().size());
        }
        return result;
    }

    /**
     * SCOPE and APPLICABILITY span IDs for a standard version.
     *
     * <p>Read from the standard's own chunks rather than retrieved by similarity: the question is "what
     * does this standard say about its scope", which is a structural lookup, not a relevance one. A
     * vector search would return the passages most like the requirement, which is a different question
     * and would miss a scope section written in different vocabulary.
     *
     * <p>Returns an empty list on failure rather than throwing. Missing scope evidence degrades the
     * decision to one a reviewer has less context for; it must not fail the requirement, because that
     * would convert a metadata problem into an unevaluated control.
     */
    /**
     * The provenance stamp for a decision: resolver version plus the override batches actually applied.
     *
     * <p><strong>Why the batch id is snapshotted (§19.2).</strong> Reopening a six-month-old comparison
     * must show the decision that ran, not today's. Overrides are governed data that changes as policy
     * owners resolve open questions, so a comparison recording only the resolver version would silently
     * re-explain itself under a newer review — and an exclusion could appear to have a rationale it never
     * had. Batches are sorted so the stamp is stable for the same inputs.
     */
    private static String decidedBy(List<EffectiveConditionResolver.EffectiveCondition> effective) {
        var batches = effective.stream()
                .map(EffectiveConditionResolver.EffectiveCondition::overrideBatchId)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .sorted()
                .toList();
        return batches.isEmpty()
                ? ApplicabilityResolver.RESOLVER_VERSION
                : ApplicabilityResolver.RESOLVER_VERSION + "+" + String.join(",", batches);
    }

    private List<String> scopeSpanIdsFor(String standardVersionId) {
        if (standardVersionId == null || standardVersionId.isBlank()) {
            return List.of();
        }
        try {
            var chunks = spanReader.chunksForVersion(standardVersionId);
            var spanIds = new ArrayList<String>();
            for (SemanticChunk chunk : chunks) {
                if (!isScopeChunk(chunk)) {
                    continue;
                }
                chunk.spanIds().forEach(spanIds::add);
                if (spanIds.size() >= MAX_SCOPE_CHUNKS * 4) {
                    // Bounded by span count rather than chunk count: one 40-span scope section would
                    // otherwise dominate the decision record.
                    break;
                }
            }
            return List.copyOf(spanIds);

        } catch (Exception e) {
            // Class name only (§35).
            log.warn("Could not load scope chunks for {} ({}); the decision is recorded without them",
                    standardVersionId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * True for a chunk that bears on scope.
     *
     * <p>Parsed through {@link ChunkType} so a legacy {@code BODY} or {@code CONTROL_SECTION} value
     * reads as {@code UNCLASSIFIED_LEGACY} and is correctly excluded — a V1 index never determined
     * whether a chunk was scope text, and treating one as such would attach arbitrary passages as
     * evidence for a scope decision.
     */
    private static boolean isScopeChunk(SemanticChunk chunk) {
        return SCOPE_TYPES.contains(ChunkType.parse(chunk.chunkType()));
    }

    /**
     * Folds applicability decisions into the coverage ledger. Architecture §17.6, §18.4.
     *
     * <p>A {@code NOT_APPLICABLE} requirement becomes a SKIPPED ledger row with its reason, which is
     * what keeps it VISIBLE and stops it counting as PENDING. Without this the requirement would either
     * vanish from the ledger — silently shrinking the review — or block completion forever as pending
     * work nobody will ever do.
     *
     * <p>UNCERTAIN requirements are NOT skipped: they are still compared, so the ledger records them
     * from their finding like any other.
     */
    public Map<String, String> skippedForLedger(List<ApplicabilityDecision> decisions) {
        var skipped = new java.util.LinkedHashMap<String, String>();
        for (var decision : decisions) {
            if (decision.status() == ApplicabilityStatus.NOT_APPLICABLE) {
                skipped.put(decision.requirementId(),
                        decision.reason() == null ? "Not applicable" : decision.reason());
            }
        }
        return skipped;
    }
}
