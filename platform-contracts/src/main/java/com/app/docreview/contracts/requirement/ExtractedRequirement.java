package com.app.docreview.contracts.requirement;

import com.app.docreview.contracts.enums.NormativeStrength;

import java.util.List;

/**
 * A requirement extracted from an immutable standard version. Architecture §12.5.
 *
 * <p>Extracted ONCE per standard version and reused by every later comparison (§12.1). Re-extracting
 * per comparison would be both wasteful and non-reproducible — two users comparing against the same
 * standard would be measured against different requirement sets.
 *
 * <p><strong>The authoritative requirement is {@code sourceSpanIds}, not
 * {@code normalizedRequirement}.</strong> §12.5 is explicit about this. The normalized text and the
 * clause decomposition exist to improve retrieval and to make comparison tractable; the source spans
 * are what a reviewer is entitled to see and what an auditor can check. If the two ever disagree,
 * the spans win.
 *
 * <p>{@code sourceControlId} preserves the standard's own numbering when it has one — {@code CHG-07},
 * {@code AC-2}, {@code ISO-A.5.1} (§12.7). A reviewer who knows the standard navigates by those, and
 * replacing them with generated IDs makes findings harder to trust, not easier.
 *
 * @param requirementId        unique within the requirement-set version
 * @param parentControlId      groups clauses under one control for display (§12.6)
 * @param sourceControlId      the standard's own identifier, when present
 * @param sourceSpanIds        AUTHORITATIVE evidence; must be non-empty (§12.8 rule 1)
 * @param normalizedRequirement a single-sentence restatement, for retrieval
 * @param atomicClauses        independently testable elements
 * @param expectedEvidenceHints what satisfying evidence tends to look like; improves retrieval recall
 * @param extractionConfidence  0.0–1.0, from the extraction model
 */
public record ExtractedRequirement(
        String requirementId,
        String parentControlId,
        String sourceControlId,
        String title,
        List<String> sourceSpanIds,
        NormativeStrength normativeStrength,
        String normalizedRequirement,
        List<AtomicClause> atomicClauses,
        Applicability applicability,
        List<String> expectedEvidenceHints,
        double extractionConfidence
) {}
