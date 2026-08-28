package com.app.docreview.contracts.extraction;

import java.util.List;

/**
 * A source region worth sending to the extraction model. Architecture §12.2, §12.3.
 *
 * <p>Carries GROUPED spans, not a single one (§12.3). A requirement routinely spans a heading, a
 * parent paragraph, and a qualifying sentence:
 *
 * <pre>
 *   4.2 Approval
 *   Changes must be approved before deployment.
 *   The approver must be independent of the implementing engineer.
 * </pre>
 *
 * <p>Sent separately, the second sentence loses what it qualifies and the first loses the
 * independence constraint — so the extracted requirement is wrong in a way no downstream validation
 * can detect. Grouping is the fix, and it is the main thing a per-sentence pipeline gets wrong.
 *
 * @param spanIds       the spans supplied as evidence; the model may cite only these
 * @param sectionPath   heading trail, for the model's context and the reviewer's navigation
 * @param combinedText  the grouped text, in reading order
 * @param signals       which lexical/structural cues fired, retained for diagnosis when extraction
 *                      output looks wrong
 * @param sourceControlId the standard's own control number when the group has one (§12.7)
 */
public record RequirementCandidate(
        String candidateId,
        CandidateCategory category,
        List<String> spanIds,
        List<String> sectionPath,
        String combinedText,
        List<String> signals,
        String sourceControlId
) {}
