package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.extraction.validation.ExtractionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ten extraction validation rules. Architecture §12.8.
 *
 * <p>These are the checks that make LLM extraction trustworthy enough to publish. Each test names the
 * consequence of the rule being absent, because a validation rule without a stated failure mode
 * tends to get "simplified" later.
 */
class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();
    private static final Set<String> ALLOWED = Set.of("SPAN-1", "SPAN-2");

    private ExtractedRequirement requirement(String id, List<String> spanIds,
                                             NormativeStrength strength,
                                             List<AtomicClause> clauses, double confidence) {
        return new ExtractedRequirement(id, "CHG", "4.2", "Independent approval",
                spanIds, strength, "An independent reviewer must approve before deployment.",
                clauses, null, List.of(), confidence);
    }

    private AtomicClause clause(String id, boolean negated) {
        return new AtomicClause(id, "independent reviewer", "approve", "change",
                "before deployment", null, negated);
    }

    private ExtractionValidator.Outcome validate(ExtractedRequirement r) {
        return validator.validate(List.of(r), ALLOWED, new HashSet<>());
    }

    @Test
    @DisplayName("a well-formed requirement is accepted")
    void acceptsValid() {
        var outcome = validate(requirement("CHG-04", List.of("SPAN-1"),
                NormativeStrength.MANDATORY, List.of(clause("CHG-04-A", false)), 0.94));
        assertThat(outcome.clean()).isTrue();
        assertThat(outcome.accepted()).hasSize(1);
    }

    @Nested
    @DisplayName("rule 2: fabricated evidence is structurally impossible")
    class SpanOwnership {

        @Test
        void rejectsSpanThatWasNotSupplied() {
            // The single most important rule. The model may return any string; one that was not
            // supplied as evidence simply fails, so an invented citation cannot reach a reviewer.
            var outcome = validate(requirement("CHG-04", List.of("SPAN-INVENTED"),
                    NormativeStrength.MANDATORY, List.of(clause("A", false)), 0.9));
            assertThat(outcome.accepted()).isEmpty();
            assertThat(outcome.errors()).anyMatch(e -> e.contains("SPAN-INVENTED"));
        }

        @Test
        void rejectsRequirementWithNoSpanAtAll() {
            var outcome = validate(requirement("CHG-04", List.of(),
                    NormativeStrength.MANDATORY, List.of(clause("A", false)), 0.9));
            assertThat(outcome.accepted()).isEmpty();
            assertThat(outcome.errors()).anyMatch(e -> e.contains("unciteable"));
        }
    }

    @Nested
    @DisplayName("rule 5: requirement ids are unique within a requirement-set version")
    class Uniqueness {

        @Test
        void rejectsDuplicateAcrossCandidates() {
            // Collisions must be caught across the whole set, not just within one model response —
            // two candidates independently proposing "CHG-04" would otherwise overwrite each other.
            var seen = new HashSet<>(Set.of("CHG-04"));
            var outcome = validator.validate(
                    List.of(requirement("CHG-04", List.of("SPAN-1"),
                            NormativeStrength.MANDATORY, List.of(clause("A", false)), 0.9)),
                    ALLOWED, seen);
            assertThat(outcome.accepted()).isEmpty();
            assertThat(outcome.errors()).anyMatch(e -> e.contains("duplicate requirementId"));
        }
    }

    @Nested
    @DisplayName("rule 6: clauses must be present and identifiable")
    class Clauses {

        @Test
        void rejectsRequirementWithNoClauses() {
            // With no clauses there is nothing to evaluate, so the requirement would silently never
            // produce a finding either way.
            var outcome = validate(requirement("CHG-04", List.of("SPAN-1"),
                    NormativeStrength.MANDATORY, List.of(), 0.9));
            assertThat(outcome.errors()).anyMatch(e -> e.contains("no atomic clauses"));
        }

        @Test
        void rejectsDuplicateClauseIds() {
            var outcome = validate(requirement("CHG-04", List.of("SPAN-1"),
                    NormativeStrength.MANDATORY,
                    List.of(clause("A", false), clause("A", false)), 0.9));
            assertThat(outcome.errors()).anyMatch(e -> e.contains("duplicate clauseId"));
        }
    }

    @Nested
    @DisplayName("rule 8: confidence must be a probability")
    class Confidence {

        @Test
        void rejectsOutOfRange() {
            var outcome = validate(requirement("CHG-04", List.of("SPAN-1"),
                    NormativeStrength.MANDATORY, List.of(clause("A", false)), 1.7));
            assertThat(outcome.errors()).anyMatch(e -> e.contains("outside 0.0-1.0"));
        }
    }

    @Nested
    @DisplayName("rule 9: definitions are not promoted to controls")
    class NonNormativePromotion {

        @Test
        void rejectsDefinitionCarryingAProhibition() {
            // Definition text is lexically almost identical to requirement text, so a model can
            // plausibly return a DEFINITION whose clause forbids something. Publishing it creates a
            // control the standard never stated.
            var outcome = validate(requirement("DEF-1", List.of("SPAN-1"),
                    NormativeStrength.DEFINITION, List.of(clause("A", true)), 0.9));
            assertThat(outcome.errors()).anyMatch(e -> e.contains("promoted to a control"));
        }

        @Test
        void allowsPlainDefinition() {
            var outcome = validate(requirement("DEF-1", List.of("SPAN-1"),
                    NormativeStrength.DEFINITION, List.of(clause("A", false)), 0.9));
            assertThat(outcome.clean()).isTrue();
        }
    }

    @Test
    @DisplayName("multiple failures are all reported, so one retry can fix everything")
    void reportsEveryProblem() {
        // §12.8 permits exactly one retry with the validation errors. Reporting only the first
        // problem would waste that retry on a requirement that still has three others.
        var outcome = validate(requirement("", List.of("SPAN-NOPE"),
                NormativeStrength.MANDATORY, List.of(), 2.0));
        assertThat(outcome.errors().size()).isGreaterThanOrEqualTo(4);
    }
}
