package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;

import java.util.List;

/**
 * The controlled applicability fixture. Architecture §12.5. Requirement item 13.
 *
 * <p>Five cases, one per decision path, built as real {@link StandardRequirementDocument} instances
 * through the production constructor rather than as hand-set fields — the document is deliberately
 * immutable (§25), and its id is DERIVED, so constructing it any other way would exercise a different
 * shape than production uses.
 *
 * <p><strong>Why a shared fixture rather than inline builders.</strong> These same five cases are
 * asserted from three angles: the resolver's verdict, the coordinator's persistence, and the runner's
 * decision to invoke the model. Defining them once means a test that claims to cover "conflicting
 * context" is provably using the same input as the others, instead of a subtly different one that
 * happens to pass.
 */
public final class ApplicabilityFixture {

    /** The confirmed document type the matching and conflicting cases are judged against. */
    public static final String CONFIRMED_TYPE = "Procedure";

    private ApplicabilityFixture() {
    }

    /** Confirmed context naming {@link #CONFIRMED_TYPE}. */
    public static ApplicabilityResolver.ConfirmedContext confirmedContext() {
        return new ApplicabilityResolver.ConfirmedContext(CONFIRMED_TYPE);
    }

    /** Nothing confirmed — the state of the live corpus for most of this project. */
    public static ApplicabilityResolver.ConfirmedContext noContext() {
        return ApplicabilityResolver.ConfirmedContext.empty();
    }

    /**
     * CASE 1 — unconditional. Expected APPLICABLE, model invoked, no scope read.
     *
     * <p>The overwhelming majority of real requirements. Must resolve without touching metadata.
     */
    public static StandardRequirementDocument unconditional() {
        return requirement("UNCOND-01", null);
    }

    /**
     * CASE 2 — condition matching confirmed context. Expected APPLICABLE, model invoked.
     *
     * <p>Scoped to {@code Procedure}, and the confirmed type IS Procedure.
     */
    public static StandardRequirementDocument matchingCondition() {
        return requirement("MATCH-01",
                new Applicability(List.of(CONFIRMED_TYPE), List.of()));
    }

    /**
     * CASE 3 — condition conflicting with confirmed context. Expected NOT_APPLICABLE, model NOT invoked.
     *
     * <p>Scoped to {@code Runbook} while the confirmed type is Procedure. The only route to exclusion:
     * a clear conflict against a value a human endorsed.
     */
    public static StandardRequirementDocument conflictingCondition() {
        return requirement("CONFLICT-01",
                new Applicability(List.of("Runbook"), List.of()));
    }

    /**
     * CASE 4 — condition with NO context to judge it. Expected UNCERTAIN, model STILL invoked.
     *
     * <p>The same requirement as case 3, judged with nothing confirmed. It must NOT be excluded:
     * absence of a label is absence of information, and dropping the requirement here would shrink the
     * review because metadata is missing rather than because the standard says so (item 9).
     */
    public static StandardRequirementDocument missingContext() {
        return requirement("MISSING-01",
                new Applicability(List.of("Runbook"), List.of()));
    }

    /**
     * CASE 5 — unstructured free-text condition. Expected UNCERTAIN with scope evidence attached.
     *
     * <p>No structured rule can evaluate "only where personal data is processed", and Phase 2
     * explicitly does not send it to a model (item 12). The honest outcome is to say so and hand the
     * reviewer the scope text.
     */
    public static StandardRequirementDocument unstructuredCondition() {
        return requirement("FREETEXT-01",
                new Applicability(List.of(), List.of("only where personal data is processed")));
    }

    /** Every case, for tests that assert a whole-review property such as no-PENDING. */
    public static List<StandardRequirementDocument> all() {
        return List.of(unconditional(), matchingCondition(), conflictingCondition(),
                missingContext(), unstructuredCondition());
    }

    /**
     * A material requirement carrying the given applicability.
     *
     * <p>MANDATORY so every case is material and therefore expected in the coverage ledger — an
     * advisory requirement is never compared (§12.2) and would drop out of the assertions for a reason
     * unrelated to applicability.
     */
    private static StandardRequirementDocument requirement(String id, Applicability applicability) {
        var extracted = new ExtractedRequirement(
                id, null, null, "Change approval",
                List.of("span-" + id), NormativeStrength.MANDATORY,
                "Changes must be approved before deployment.",
                List.of(), applicability, List.of("approval record"), 0.9);
        return new StandardRequirementDocument("std-1", "v-1", "rs-1", extracted, null, null, null);
    }
}
