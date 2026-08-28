package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;

import java.util.List;

/**
 * A persisted, auditable record of why a requirement was or was not assessed. Architecture §12.5, §17.6.
 *
 * <p><strong>Why this is persisted rather than recomputed.</strong> An applicability decision depends on
 * the confirmed assessment context at the time the review ran. Recomputing it when a reviewer reopens
 * history would silently produce a different answer once the context, the resolver's rules, or the
 * document's metadata changed — and the reviewer would be looking at a report that no longer matches the
 * one they signed off. §19.2 requires a completed review to resolve exactly what produced it.
 *
 * <p><strong>Every non-default outcome carries its evidence.</strong> An exclusion nobody can inspect is
 * indistinguishable from a requirement the platform lost, and it silently shrinks what the review
 * covered. So {@code NOT_APPLICABLE} and {@code UNCERTAIN} both record a reason, the SCOPE and
 * APPLICABILITY spans a reviewer should read, and — for UNCERTAIN — exactly which context fields were
 * missing.
 *
 * @param requirementId        the requirement this decision is about
 * @param status               the outcome. Separate from {@code ComparisonStatus} by design (§12.5)
 * @param reason               why, in reviewer-facing terms. Null only for the unconditional
 *                             APPLICABLE case, where a reason on every requirement would be noise
 * @param scopeSpanIds         SCOPE and APPLICABILITY span IDs a reviewer should read to judge the
 *                             decision themselves. Attached for UNCERTAIN in particular, where the
 *                             platform is explicitly declining to interpret the condition (§12)
 * @param missingContextFields the confirmed-context fields that would have resolved this, e.g.
 *                             {@code ["documentType"]}. Empty unless UNCERTAIN. Names the fix rather
 *                             than leaving the reviewer to guess what to supply
 * @param conditions           the requirement's stated conditions, verbatim, so the reviewer sees what
 *                             the standard actually said rather than a paraphrase
 * @param decidedBy            the resolver version that produced this. A rule change must not silently
 *                             reinterpret an existing decision (§19.2)
 */
public record ApplicabilityDecision(
        String requirementId,
        ApplicabilityStatus status,
        String reason,
        List<String> scopeSpanIds,
        List<String> missingContextFields,
        List<String> conditions,
        String decidedBy) {

    public ApplicabilityDecision {
        // Defensive copies: the decision is persisted and replayed, and a caller mutating a list
        // afterwards would change what a completed review claims about its own scope.
        scopeSpanIds = scopeSpanIds == null ? List.of() : List.copyOf(scopeSpanIds);
        missingContextFields = missingContextFields == null ? List.of() : List.copyOf(missingContextFields);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** The unconditional case, reached without any analysis. */
    public static ApplicabilityDecision applicable(String requirementId, String decidedBy) {
        return new ApplicabilityDecision(requirementId, ApplicabilityStatus.APPLICABLE,
                null, List.of(), List.of(), List.of(), decidedBy);
    }

    /** True when the comparison model should be invoked for this requirement. */
    public boolean shouldCompare() {
        return status.shouldCompare();
    }

    /**
     * True when the reviewer must look at this scope decision.
     *
     * <p>Both non-default states qualify, for opposite reasons: an exclusion removed a requirement from
     * the review, and UNCERTAIN means the platform could not tell whether it should have.
     */
    public boolean needsHumanReview() {
        return status.needsScopeReview();
    }
}
