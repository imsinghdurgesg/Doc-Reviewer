package com.app.docreview.contracts.requirement;

import com.app.docreview.contracts.enums.ConditionRole;

import java.util.List;

/**
 * One condition on a requirement, with its role and its evidence. Architecture §12.5, §16.5.
 *
 * <p><strong>Replaces a bare condition string.</strong> Applicability previously received
 * {@code List<String> conditions} and could say nothing about what any of them meant — so every
 * condition took the same path, and the only honest outcome was {@code UNCERTAIN}. A role plus the
 * passage it came from is what lets a timing constraint be compared normally while a genuine scope gate
 * is resolved against confirmed context.
 *
 * <p><strong>Every condition carries evidence, always.</strong> {@code conditionSourceSpanIds} is the
 * standard passage the condition was extracted FROM, so an applicability decision is never evidence-free
 * even when the standard has no dedicated scope section. That was the previous state: UNCERTAIN
 * decisions arrived with zero spans because the corpus had no SCOPE-typed chunks, leaving a reviewer a
 * conclusion with nothing to check it against.
 *
 * @param text          the condition verbatim, as the standard words it. A paraphrase cannot be checked
 *                      against the source, and the reviewer resolving an UNCERTAIN decision reads this
 * @param role          what the condition does. Only {@link ConditionRole#SCOPE_GATE} can exclude
 * @param documentTypes for a SCOPE_GATE limited to named document kinds. Empty otherwise — and empty is
 *                      the norm, since most scope gates are prose rather than a type list
 * @param conditionSourceSpanIds the standard span(s) this condition was read from. THE immediate
 *                      evidence, available without any chunk reprocessing
 */
public record RequirementCondition(
        String text,
        ConditionRole role,
        List<String> documentTypes,
        List<String> conditionSourceSpanIds) {

    public RequirementCondition {
        // Defensive copies: conditions are persisted with a requirement set and replayed, and a caller
        // mutating a list afterwards would change what a completed review claims the standard said.
        documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
        conditionSourceSpanIds = conditionSourceSpanIds == null
                ? List.of() : List.copyOf(conditionSourceSpanIds);
        // UNKNOWN rather than null, so no caller has to null-check before asking whether this can
        // exclude — and a missing role can never be mistaken for a scope gate.
        role = role == null ? ConditionRole.UNKNOWN : role;
    }

    /** True when this condition may lead to {@code NOT_APPLICABLE}. */
    public boolean canExclude() {
        return role.canExclude();
    }

    /**
     * True when this condition should be shown to the comparison model.
     *
     * <p>A qualifier, trigger, deadline or waiver route changes what compliance looks like, so the model
     * must see it. A scope gate is answered before comparison runs.
     */
    public boolean informsComparison() {
        return role.informsComparison();
    }
}
