package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.RequirementCondition;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver.ConfirmedContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exclusion requires a MACHINE-DECIDABLE scope gate. Architecture §12.5. Phase 2.1 review.
 *
 * <p><strong>Why this test exists.</strong> A human review of 115 model-predicted SCOPE_GATEs
 * reclassified 113 — 2% precision. The classifier was systematically reading risk tiers and change
 * classes ("emergency changes", "for high-risk changes") as scope questions, when they name a subset of
 * work the document should still govern.
 *
 * <p>No requirement was actually excluded, because none of the 115 carried a {@code documentTypes} list
 * and only that branch reaches {@code NOT_APPLICABLE}. But that safety came from the DATA, not from the
 * design — a future extraction that attached a type list to a mis-classified gate would have excluded a
 * real control.
 *
 * <p>These tests pin the bound explicitly, so the protection no longer depends on the model happening
 * not to populate a field.
 */
class ScopeGateExclusionBoundTest {

    private final ApplicabilityResolver resolver = new ApplicabilityResolver();

    private static Applicability proseGate(String text) {
        return new Applicability(List.of(), List.of(text),
                List.of(new RequirementCondition(text, ConditionRole.SCOPE_GATE,
                        // No documentTypes: prose only, which is how every one of the 115 arrived.
                        List.of(), List.of("STD-SPAN-1"))));
    }

    private static Applicability typedGate(String text, String... types) {
        return new Applicability(List.of(types), List.of(text),
                List.of(new RequirementCondition(text, ConditionRole.SCOPE_GATE,
                        List.of(types), List.of("STD-SPAN-1"))));
    }

    @Test
    @DisplayName("a PROSE scope gate can never exclude, whatever the confirmed context")
    void proseGateNeverExcludes() {
        // The real corpus shape. "Emergency changes" as a SCOPE_GATE with no type list must reach
        // UNCERTAIN — the platform cannot decide it, and guessing would remove a control.
        for (var context : List.of(
                ConfirmedContext.empty(),
                new ConfirmedContext("Procedure"),
                new ConfirmedContext("Runbook"),
                new ConfirmedContext("something entirely unrelated"))) {

            var decision = resolver.resolve(proseGate("Emergency changes"), context);

            assertThat(decision.status())
                    .as("a prose scope gate must not exclude under context %s", context)
                    .isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.shouldCompare()).isTrue();
        }
    }

    @Test
    @DisplayName("the real over-classified phrases would not have excluded anything")
    void realCorpusPhrasesNeverExclude() {
        // The exact texts the review found. Kept as a regression fixture: if a future change made any
        // of these excludable, a real control would silently leave the review.
        var phrases = List.of(
                "Emergency changes",
                "For medium- and high-risk changes",
                "Changes using privileged access or automated production deployment",
                "All production changes",
                "Low-risk changes",
                "Changes with potential service or user impact",
                "Changes affecting persistent data",
                "Material changes");

        for (String phrase : phrases) {
            var decision = resolver.resolve(proseGate(phrase), new ConfirmedContext("Procedure"));

            assertThat(decision.status())
                    .as("%s must not exclude", phrase)
                    .isNotEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
        }
    }

    @Test
    @DisplayName("a TYPED scope gate still excludes on a clear conflict")
    void typedGateStillExcludes() {
        // The bound must not disable the feature. A genuine document-kind limit against confirmed
        // context is exactly the case exclusion exists for.
        var decision = resolver.resolve(
                typedGate("For leased hardware", "Runbook"), new ConfirmedContext("Procedure"));

        assertThat(decision.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a typed scope gate matching confirmed context applies")
    void typedGateMatchApplies() {
        assertThat(resolver.resolve(typedGate("For procedures", "Procedure"),
                new ConfirmedContext("Procedure")).status())
                .isEqualTo(ApplicabilityStatus.APPLICABLE);
    }

    @Test
    @DisplayName("the surviving personal-data gates behave as prose gates")
    void personalDataGatesAreProse() {
        // The 2 of 115 a human kept as SCOPE_GATE. They are genuinely about the governed material —
        // but they are still prose, so they resolve UNCERTAIN and are compared. Correct: the platform
        // cannot tell from a type list whether this document handles personal data.
        for (String phrase : List.of("for records containing personal data", "containing personal data")) {
            var decision = resolver.resolve(proseGate(phrase), new ConfirmedContext("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.conditionSourceSpanIds())
                    .as("and it carries its evidence")
                    .containsExactly("STD-SPAN-1");
        }
    }
}
