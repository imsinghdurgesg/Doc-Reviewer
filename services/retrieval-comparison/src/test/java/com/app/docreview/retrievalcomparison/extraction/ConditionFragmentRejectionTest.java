package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.contracts.requirement.RequirementCondition;
import com.app.docreview.retrievalcomparison.extraction.validation.ExtractionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 11: an extracted condition must be a whole testable clause. Architecture §12.8, §16.5.
 *
 * <p><strong>The defect.</strong> The extractor returned {@code "When it is"} for the clause "When the
 * change is a material security or privacy change, a qualified reviewer independent of the implementer
 * must assess...". Technically verbatim — it IS a substring — while dropping the entire substantive
 * test. Nothing downstream can evaluate it, no reviewer can judge it, and it consumed a policy-owner
 * queue slot for a question the standard had already answered.
 *
 * <p><strong>The harder half of this rule is what it must NOT reject.</strong> Real conditions can be
 * very short — "before signing", "where required", "at authorization" all state a testable fact in two
 * or three words. A length threshold would have rejected those while still passing a long-but-empty
 * fragment, so the check is a closed list of bare connectives instead.
 */
class ConditionFragmentRejectionTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    private static final Set<String> SPANS = Set.of("SPAN-1");

    /** A requirement that passes every other rule, carrying the condition under test. */
    private static ExtractedRequirement withCondition(RequirementCondition condition) {
        return new ExtractedRequirement(
                "CHG-14-2", "14.2", "14", "Security review of material changes",
                List.of("SPAN-1"),
                NormativeStrength.MANDATORY,
                "When the change is a material security or privacy change, a qualified reviewer "
                        + "independent of the implementer must assess rollback readiness.",
                List.of(new AtomicClause("CHG-14-2-1", "Reviewer", "assess",
                        "rollback readiness", "", "", false)),
                new Applicability(List.of(), List.of(), List.of(condition)),
                List.of(), 0.9);
    }

    private List<String> problemsFor(RequirementCondition condition) {
        // Empty seen-IDs: each case validates one requirement in isolation, so uniqueness cannot fire
        // and every reported problem is attributable to the condition under test.
        var outcome = validator.validate(List.of(withCondition(condition)), SPANS, new java.util.HashSet<>());
        return outcome.errors();
    }

    private static RequirementCondition condition(String text) {
        return new RequirementCondition(text, ConditionRole.CONDITIONAL_OBLIGATION,
                List.of(), List.of("SPAN-1"));
    }

    @Nested
    @DisplayName("fragments are rejected")
    class Fragments {

        @Test
        @DisplayName("the exact defect: \"When it is\"")
        void rejectsTheObservedFragment() {
            assertThat(problemsFor(condition("When it is")))
                    .anySatisfy(p -> assertThat(p).contains("fragment"));
        }

        @Test
        @DisplayName("bare connectives of every shape are rejected")
        void rejectsBareConnectives() {
            for (var text : List.of("when", "When the", "if it is", "where the", "For an",
                    "unless the", "provided that", "in the case of")) {
                assertThat(problemsFor(condition(text)))
                        .as("%s states nothing being tested", text)
                        .anySatisfy(p -> assertThat(p).contains("fragment"));
            }
        }

        @Test
        @DisplayName("case and trailing punctuation do not evade the check")
        void normalisesBeforeMatching() {
            for (var text : List.of("WHEN IT IS", "  when it is  ", "when it is,", "When it is.")) {
                assertThat(problemsFor(condition(text)))
                        .as("%s is the same fragment", text)
                        .anySatisfy(p -> assertThat(p).contains("fragment"));
            }
        }

        @Test
        @DisplayName("the error names the condition so the author can find it")
        void errorIdentifiesTheCondition() {
            assertThat(problemsFor(condition("When it is")))
                    .anySatisfy(p -> assertThat(p).contains("When it is"));
        }
    }

    @Nested
    @DisplayName("legitimate conditions are NOT rejected")
    class Legitimate {

        @Test
        @DisplayName("genuinely short conditions from the real corpus pass")
        void shortButTestableConditionsPass() {
            // Every one of these is in the live V3 corpus. A length-based rule would have failed them
            // all, which would be a worse regression than the bug it fixed.
            for (var text : List.of("before signing", "at authorization", "where required",
                    "before deployment", "after deployment", "where relevant", "Low-risk changes",
                    "Emergency changes", "Material changes", "High-risk changes")) {
                assertThat(problemsFor(condition(text)))
                        .as("%s states a testable fact", text)
                        .noneSatisfy(p -> assertThat(p).contains("fragment"));
            }
        }

        @Test
        @DisplayName("the repaired form of the defect passes")
        void theCorrectExtractionPasses() {
            assertThat(problemsFor(condition("when the change is a material security or privacy change")))
                    .noneSatisfy(p -> assertThat(p).contains("fragment"));
        }

        @Test
        @DisplayName("a connective followed by substance passes")
        void connectivePlusSubstancePasses() {
            // Shares a prefix with a rejected fragment. Matching on prefix rather than whole value
            // would reject these, and most real conditions begin this way.
            for (var text : List.of("when the change is material", "if it is a production change",
                    "for an emergency change", "where the system is externally reachable")) {
                assertThat(problemsFor(condition(text)))
                        .as("%s is a complete condition", text)
                        .noneSatisfy(p -> assertThat(p).contains("fragment"));
            }
        }

        @Test
        @DisplayName("an empty condition text is not treated as a fragment")
        void emptyTextIsAllowed() {
            // A documentTypes-only condition carries no prose. Demanding text here would push the
            // extractor toward synthesising standard wording that does not exist.
            var typed = new RequirementCondition("", ConditionRole.SCOPE_GATE,
                    List.of("Runbook"), List.of("SPAN-1"));
            assertThat(problemsFor(typed)).noneSatisfy(p -> assertThat(p).contains("fragment"));
        }
    }

    @Nested
    @DisplayName("evidence is mandatory")
    class Evidence {

        @Test
        @DisplayName("a condition citing no span is rejected")
        void requiresASourceSpan() {
            var unsourced = new RequirementCondition("Emergency changes",
                    ConditionRole.CONDITIONAL_OBLIGATION, List.of(), List.of());

            // §16.5: applicability is the one decision that can remove a control from a review, so a
            // reviewer must be able to check it against the standard rather than take it on trust.
            assertThat(problemsFor(unsourced))
                    .anySatisfy(p -> assertThat(p).contains("cites no source span"));
        }

        @Test
        @DisplayName("a well-formed sourced condition produces no problems at all")
        void cleanConditionIsClean() {
            assertThat(problemsFor(condition("Emergency changes"))).isEmpty();
        }
    }

    @Test
    @DisplayName("the schema version records that this rule ran")
    void schemaVersionMoved() {
        // A stored set says which validator accepted it. Without the bump, a V1 set containing
        // fragments and a V2 set proven free of them would be indistinguishable.
        assertThat(ExtractionValidator.SCHEMA_VERSION).isEqualTo("REQ-SCHEMA-V2");
    }
}
