package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.RequirementCondition;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver.ConfirmedContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Condition ROLE decides the applicability path. Architecture §12.5. Phase 2.1.
 *
 * <p><strong>The problem this fixes.</strong> Applicability treated "the requirement states a condition"
 * as one category. On a real corpus that meant 116 of 254 requirements — nearly half a standard — went
 * onto the applicability-review path, and every one of them was within reach of {@code NOT_APPLICABLE}.
 *
 * <p>But the conditions were not alike. "Within one business day" is part of the obligation. "When
 * technically feasible" qualifies how it is met. Only something like "For leased hardware" speaks to
 * whether the requirement applies at all. Excluding a requirement because it said "when technically
 * feasible" would silently remove a real control.
 *
 * <p>The single most important assertion here is {@link OnlyScopeGateExcludes#noOtherRoleCanExclude} —
 * it walks every role and proves that only SCOPE_GATE can produce an exclusion.
 */
class ConditionRoleResolutionTest {

    private final ApplicabilityResolver resolver = new ApplicabilityResolver();

    private static ConfirmedContext confirmed(String documentType) {
        return new ConfirmedContext(documentType);
    }

    /** A condition with a role, its own source span, and no document-type list. */
    private static Applicability condition(String text, ConditionRole role) {
        return new Applicability(List.of(), List.of(text),
                List.of(new RequirementCondition(text, role, List.of(), List.of("STD-SPAN-208"))));
    }

    /** A SCOPE_GATE limited to named document types — the only machine-decidable shape. */
    private static Applicability scopeGate(String text, String... documentTypes) {
        return new Applicability(List.of(documentTypes), List.of(text),
                List.of(new RequirementCondition(text, ConditionRole.SCOPE_GATE,
                        List.of(documentTypes), List.of("STD-SPAN-208"))));
    }

    @Nested
    @DisplayName("only SCOPE_GATE can ever exclude")
    class OnlyScopeGateExcludes {

        @Test
        @DisplayName("no role other than SCOPE_GATE produces NOT_APPLICABLE")
        void noOtherRoleCanExclude() {
            // THE invariant. Walks every role rather than sampling, so a role added later cannot
            // acquire exclusion power by being forgotten here.
            for (ConditionRole role : ConditionRole.values()) {
                if (role == ConditionRole.SCOPE_GATE) {
                    continue;
                }
                var decision = resolver.resolve(
                        condition("some condition", role), confirmed("Procedure"));

                assertThat(decision.status())
                        .as("%s must never exclude a requirement", role)
                        .isNotEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
                assertThat(decision.shouldCompare())
                        .as("%s must still be compared", role)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the enum itself grants exclusion power to SCOPE_GATE alone")
        void canExcludeIsScopeGateOnly() {
            // Expressed in one place so the restriction cannot drift between the enum and the resolver.
            assertThat(java.util.Arrays.stream(ConditionRole.values())
                    .filter(ConditionRole::canExclude).toList())
                    .containsExactly(ConditionRole.SCOPE_GATE);
        }
    }

    @Nested
    @DisplayName("non-scope roles resolve to APPLICABLE and are compared with their condition")
    class NonScopeRoles {

        @Test
        @DisplayName("IMPLEMENTATION_QUALIFIER applies — 'when technically feasible'")
        void implementationQualifierApplies() {
            // Appeared 8 times in the real corpus. It bounds the expected implementation; the
            // obligation stands either way.
            var decision = resolver.resolve(
                    condition("when technically feasible", ConditionRole.IMPLEMENTATION_QUALIFIER),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(decision.conditions())
                    .as("the comparison must see the qualifier to judge partial alignment fairly")
                    .contains("when technically feasible");
        }

        @Test
        @DisplayName("TEMPORAL_OR_SEQUENCE applies — 'within one business day'")
        void temporalApplies() {
            var decision = resolver.resolve(
                    condition("within one business day", ConditionRole.TEMPORAL_OR_SEQUENCE),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(decision.conditions()).contains("within one business day");
        }

        @Test
        @DisplayName("CONDITIONAL_OBLIGATION applies — 'if testing fails, initiate rollback'")
        void conditionalObligationApplies() {
            // Reading this as a scope limit would excuse a document with no rollback procedure at all,
            // which is precisely the gap the review exists to find.
            var decision = resolver.resolve(
                    condition("if testing fails, initiate rollback",
                            ConditionRole.CONDITIONAL_OBLIGATION),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("EXCEPTION_OR_WAIVER applies — 'unless an approved exception exists'")
        void waiverApplies() {
            // The process should DEFINE the exception route. Excluding the requirement would let a
            // document with no exception process look compliant.
            var decision = resolver.resolve(
                    condition("unless an approved exception exists",
                            ConditionRole.EXCEPTION_OR_WAIVER),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("a non-scope role needs NO confirmed context")
        void nonScopeIgnoresContext() {
            // These roles do not consult context at all, so a corpus with nothing confirmed still gets
            // them compared as APPLICABLE rather than degraded to UNCERTAIN.
            var decision = resolver.resolve(
                    condition("within one business day", ConditionRole.TEMPORAL_OR_SEQUENCE),
                    ConfirmedContext.empty());

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }
    }

    @Nested
    @DisplayName("UNKNOWN is UNCERTAIN, never SCOPE_GATE")
    class UnknownRole {

        @Test
        @DisplayName("an unclassified condition is UNCERTAIN and still compared")
        void unknownIsUncertain() {
            var decision = resolver.resolve(
                    condition("some unclassifiable clause", ConditionRole.UNKNOWN),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.shouldCompare()).isTrue();
        }

        @Test
        @DisplayName("a malformed role string parses to UNKNOWN, not SCOPE_GATE")
        void malformedRoleCannotExclude() {
            // A garbled or renamed value must not acquire exclusion power by accident.
            assertThat(ConditionRole.parse("SCOPE_GATEE")).isEqualTo(ConditionRole.UNKNOWN);
            assertThat(ConditionRole.parse(null)).isEqualTo(ConditionRole.UNKNOWN);
            assertThat(ConditionRole.parse("")).isEqualTo(ConditionRole.UNKNOWN);
            assertThat(ConditionRole.parse("scope_gate"))
                    .as("a correctly-named value should still parse")
                    .isEqualTo(ConditionRole.SCOPE_GATE);
        }
    }

    @Nested
    @DisplayName("SCOPE_GATE resolution requires affirmative evidence")
    class ScopeGateResolution {

        @Test
        @DisplayName("a matching confirmed type is APPLICABLE")
        void matchApplies() {
            assertThat(resolver.resolve(scopeGate("For procedures", "Procedure"),
                    confirmed("Procedure")).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("a conflicting confirmed type is NOT_APPLICABLE")
        void conflictExcludes() {
            var decision = resolver.resolve(
                    scopeGate("For leased hardware", "Runbook"), confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(decision.reason()).contains("Runbook", "Procedure");
        }

        @Test
        @DisplayName("ABSENCE of confirmed context is UNCERTAIN, never exclusion")
        void absenceIsNotExclusion() {
            // The protected invariant. A document not mentioning leased hardware does not prove the
            // leased-hardware controls are inapplicable — it may mean they are MISSING. Only a
            // confirmed statement to the contrary justifies exclusion.
            var decision = resolver.resolve(
                    scopeGate("For leased hardware", "Runbook"), ConfirmedContext.empty());

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.missingContextFields()).containsExactly("documentType");
        }

        @Test
        @DisplayName("a PROSE scope gate with no type list is UNCERTAIN, not guessed")
        void proseScopeGateIsUncertain() {
            // "applies only where personal data is processed" is a genuine scope gate that no
            // structured rule can evaluate. Phase 2.1 does not send it to a model, so the honest
            // outcome is UNCERTAIN with the condition's own evidence attached.
            var decision = resolver.resolve(
                    condition("only where personal data is processed", ConditionRole.SCOPE_GATE),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.conditionSourceSpanIds()).containsExactly("STD-SPAN-208");
        }
    }

    @Nested
    @DisplayName("every decision carries condition evidence")
    class ConditionEvidence {

        @Test
        @DisplayName("the condition's own source span is always attached")
        void conditionSpanAlwaysPresent() {
            // Layer 1 evidence, available with no chunk reprocessing. Previously an UNCERTAIN decision
            // on a standard with no SCOPE chunks carried zero spans — a conclusion with nothing behind
            // it.
            for (ConditionRole role : ConditionRole.values()) {
                var decision = resolver.resolve(condition("a clause", role), confirmed("Procedure"));

                assertThat(decision.conditionSourceSpanIds())
                        .as("%s must retain its source evidence", role)
                        .containsExactly("STD-SPAN-208");
            }
        }

        @Test
        @DisplayName("an unconditional requirement attaches nothing")
        void unconditionalHasNoEvidence() {
            // The fast path stays free: no condition means no evidence to gather.
            var decision = resolver.resolve(null, confirmed("Procedure"));

            assertThat(decision.conditionSourceSpanIds()).isEmpty();
            assertThat(decision.needsScopeContext()).isFalse();
        }
    }

    @Nested
    @DisplayName("legacy V1 and V2 data still reads safely")
    class LegacyData {

        @Test
        @DisplayName("a V2 bare condition reads as UNKNOWN, so it cannot exclude")
        void v2ConditionIsUnknown() {
            // V2 recorded condition text but never its role. Defaulting those to SCOPE_GATE would
            // retroactively give every legacy condition the power to remove a requirement.
            var v2 = new Applicability(List.of(), List.of("some legacy condition"));

            assertThat(v2.effectiveConditions())
                    .singleElement()
                    .satisfies(c -> assertThat(c.role()).isEqualTo(ConditionRole.UNKNOWN));
            assertThat(resolver.resolve(v2, confirmed("Procedure")).status())
                    .isEqualTo(ApplicabilityStatus.UNCERTAIN);
        }

        @Test
        @DisplayName("a V2 documentTypes list is treated as a SCOPE_GATE")
        void v2DocumentTypesIsAScopeGate() {
            // The one safe inference: a requirement limited to named document kinds is a scope gate by
            // construction, and the V2 resolver already treated it as one — so behaviour is preserved.
            var v2 = new Applicability(List.of("Runbook"), List.of());

            assertThat(v2.effectiveConditions())
                    .singleElement()
                    .satisfies(c -> assertThat(c.role()).isEqualTo(ConditionRole.SCOPE_GATE));
            assertThat(resolver.resolve(v2, confirmed("Procedure")).status())
                    .isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
        }

        @Test
        @DisplayName("a V1 null applicability is unconditional")
        void v1IsUnconditional() {
            assertThat(resolver.resolve(null, ConfirmedContext.empty()).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }
    }
}
