package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;

import java.util.List;

/**
 * Raw structured output from the comparison model. Architecture §16.4.
 *
 * <p><strong>Untrusted until validated.</strong> §18 requires schema validation, span-ownership
 * validation, and status/evidence consistency checks before any of this reaches a reviewer. This
 * record is the wire shape, not a finding.
 *
 * <p>Note what is ABSENT: no quoted text, no page numbers, and no severity.
 *
 * <ul>
 *   <li>Evidence is returned as span IDs and hydrated from storage (§16.5), so the model cannot
 *       invent a quotation or cite a page that does not exist.</li>
 *   <li>Severity comes from standard metadata and administrator rules (§17.7). A model-assigned
 *       severity would make risk ranking unauditable.</li>
 * </ul>
 *
 * <p>{@code satisfiedClauseIds} / {@code unsatisfiedClauseIds} are what make a PARTIAL verdict
 * actionable — they name which clause failed, so §18.5 can aggregate clauses to a parent control
 * without losing the detail.
 *
 * @param needsHumanReview the model's own signal that evidence did not justify a confident
 *                         conclusion; routes to review rather than silently reporting a verdict
 */
public record ComparisonModelOutput(
        String requirementId,
        ComparisonStatus status,
        GapType gapType,
        List<String> satisfiedClauseIds,
        List<String> unsatisfiedClauseIds,
        List<String> standardSpanIds,
        List<String> processSpanIds,
        String reason,
        String recommendedAction,
        double confidence,
        boolean needsHumanReview
) {

    // DELIBERATELY ABSENT: evaluationLimitation and unmappedStandardObligations.
    //
    // A withdrawn revision let the model report that OUR extraction of the standard was incomplete.
    // Because the branch was reachable on every requirement, the model took it whenever a comparison
    // was hard — for ordinary evidence uncertainty, for obligations a clause already covered, and for
    // reasoning it could not map onto the clause list. Across 191 requirements, evaluated fell from 187
    // to 46; two prompt revisions did not fix it, because each misfire costs a whole control and the
    // rate multiplies across the set.
    //
    // The model judges the PROCESS against the supplied requirement. Whether the requirement was
    // extracted correctly is not a question it can answer — it cannot see the extractor, the provenance
    // spans it was not given, or the rest of the set. That judgement belongs to requirement-integrity
    // validation at ingestion, which is deterministic and runs BEFORE any model call.
    //
    // Keeping the fields off this record is what makes that structural rather than a matter of prompt
    // wording: there is no field for the model to populate.
}
