package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.retrievalcomparison.extraction.prompt.ExtractionPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extraction prompt asks for applicability, and asks conservatively. Architecture §12.5, §12.4.
 *
 * <p><strong>Why this needed a test at all.</strong> {@code Applicability} has been a field on
 * {@code ExtractedRequirement} — and therefore in the generated response schema — for as long as the
 * record has existed, so the model was always ABLE to return it. But nothing in the prompt asked, so it
 * never did: 288 extracted requirements had the field null. The Phase 2 workflow then had nothing to
 * decide on, and every conditional requirement took the unconditional fast path.
 *
 * <p>A schema field with no instruction is silently dead. That is what these assertions pin.
 *
 * <p><strong>The direction of caution matters more than the presence of the rule.</strong> An invented
 * condition can EXCLUDE a real obligation from a review, so the prompt has to push toward null. A rule
 * that merely said "populate applicability" would be worse than none.
 */
class ExtractionPromptApplicabilityTest {

    private String system() {
        return ExtractionPrompt.system("test-nonce");
    }

    /**
     * The prompt with runs of whitespace collapsed.
     *
     * <p>Necessary because the prompt is a text block whose rules wrap across lines with continuation
     * backslashes and indentation. A phrase that reads as one sentence to the model is split by a
     * newline plus twenty spaces in the source, so a literal substring assertion fails on text that is
     * present and correct — which is a test problem, not a prompt problem.
     */
    private String flattened() {
        return system().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("the prompt asks for applicability at all")
    void asksForApplicability() {
        // Without this the schema field stays null forever and the whole applicability workflow is
        // inert, however correct the resolver is.
        assertThat(system()).contains("applicability");
        assertThat(system()).contains("documentTypes");
        assertThat(system()).contains("structuredConditions");
        // V3: the role is the most consequential field, so the prompt must name every value.
        for (var role : com.app.docreview.contracts.enums.ConditionRole.values()) {
            assertThat(system())
                    .as("the prompt must define %s", role)
                    .contains(role.name());
        }
    }

    @Test
    @DisplayName("the prompt tells the model to leave it NULL unless the requirement states a limit")
    void defaultsToNull() {
        // The safety direction. Most requirements apply unconditionally, and the cost of a false
        // condition is an excluded obligation — far worse than a missed one, which merely leaves the
        // requirement compared as normal.
        assertThat(flattened())
                .as("the model must be told null is the normal answer")
                .containsIgnoringCase("Leave it null otherwise");
        assertThat(flattened())
                .as("the consequence must be stated, not just the rule")
                .containsIgnoringCase("can exclude a real obligation");
    }

    @Test
    @DisplayName("the prompt forbids inferring a document type from subject matter")
    void forbidsInference() {
        // The specific failure this prevents: a change-management requirement mentioning "runbook" in
        // passing gets documentTypes: ["Runbook"], and is then excluded from every review of a
        // procedure. The requirement never stated a limit; the model inferred one.
        assertThat(flattened()).containsIgnoringCase("Do NOT infer a type from subject matter");
        assertThat(flattened())
                .as("mentioning a type is not the same as being limited to it")
                .containsIgnoringCase("merely because the requirement mentions it");
    }

    @Test
    @DisplayName("the prompt says a universal scope is NOT a condition")
    void universalScopeIsNotACondition() {
        // "applies to all systems" states a condition syntactically while imposing none. Recording
        // "all" would make the resolver compare it against a document type and exclude everything —
        // the resolver guards this too, but the prompt should not produce it in the first place.
        assertThat(flattened()).containsIgnoringCase("states NO limit");
    }

    @Test
    @DisplayName("the prompt asks for the condition VERBATIM")
    void conditionsAreVerbatim() {
        // A paraphrased condition cannot be checked against the standard. The reviewer resolving an
        // UNCERTAIN decision reads this text, so it has to be what the standard actually says.
        assertThat(flattened()).containsIgnoringCase("verbatim");
    }

    @Test
    @DisplayName("the prompt version records that the output shape changed")
    void promptVersionBumped() {
        // A V1 requirement set has null applicability throughout, which is indistinguishable from "the
        // model found no conditions" unless the version says which prompt ran (§19.2).
        assertThat(ExtractionPrompt.PROMPT_VERSION).isEqualTo("REQ-EXTRACT-V5");
    }

    @Test
    @DisplayName("V4 states the subset-of-work test that a human review produced")
    void scopeGateTestIsStated() {
        // A review of 115 predicted SCOPE_GATEs reclassified 113. The failure was systematic, and it
        // moved: V4 named risk tiers and change classes, and its shadow run then left six false gates
        // that were all MECHANISM phrases ("changes using privileged access"). V5 states a two-part
        // test and lists the mechanism categories. Losing any of this wording reintroduces a known
        // classifier failure, so each part is pinned.
        var prompt = flattened();

        // Part (a): the condition must decide applicability at all, not which work triggers the duty.
        assertThat(prompt)
                .as("the applies-at-all test must be stated, not implied")
                .containsIgnoringCase("decides whether the requirement applies to the ASSESSMENT AT");
        // Part (b): and it must be decidable from confirmed context. Without this a model can invent a
        // boundary nothing can evaluate — which is how an unevaluable gate reaches a reviewer.
        assertThat(prompt)
                .as("the boundary must be evaluable from confirmed assessment context")
                .containsIgnoringCase("CONFIRMED ASSESSMENT CONTEXT");

        // Leading conditional wording is explicitly not a signal. This is the V4 failure in one line.
        assertThat(prompt)
                .as("conditional wording alone must not imply a scope gate")
                .containsIgnoringCase("DO NOT classify as SCOPE_GATE merely because the phrase begins");

        // The mechanism categories, each named. A category dropped from this list is a category the
        // extractor will start misclassifying again.
        assertThat(prompt).containsIgnoringCase("ACCESS METHOD");
        assertThat(prompt).containsIgnoringCase("DEPLOYMENT MECHANISM");
        assertThat(prompt).containsIgnoringCase("OPERATIONAL TRIGGER");
        assertThat(prompt).containsIgnoringCase("RISK-TRIGGERED BRANCH");
        assertThat(prompt).containsIgnoringCase("IMPLEMENTATION MECHANISM");

        assertThat(prompt)
                .as("the worked example is what makes the rule usable")
                // "has a GAP" in V5: the subject is the DOCUMENT, which reads more clearly than V4's
                // "is a GAP" where the subject was the condition. Asserted on the substantive phrase
                // rather than the verb, so a future rewording that keeps the meaning does not fail.
                .containsIgnoringCase("GAP, not an exemption");

        // The exact phrase whose misclassification survived V4, now given a worked answer.
        assertThat(prompt)
                .as("the mechanism phrase that V4 got wrong must appear with its correct role")
                .containsIgnoringCase("Changes using privileged access");
    }

    @Test
    @DisplayName("rule 6 still forbids promoting scope statements to requirements")
    void scopeStatementsAreStillNotRequirements() {
        // Annotating a requirement with its condition is not the same as turning a scope section into
        // a requirement. Both rules have to hold, and adding rule 11 must not have weakened rule 6.
        assertThat(flattened())
                .containsIgnoringCase("Do not promote definitions, examples, rationale, scope statements");
    }
}
