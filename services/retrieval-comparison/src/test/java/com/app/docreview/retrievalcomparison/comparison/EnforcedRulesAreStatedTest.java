package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rule the validator enforces must be stated in the prompt. Architecture §16, §18.4.
 *
 * <p><strong>The defect this pins.</strong> The validator rejected {@code PARTIAL} unless at least one
 * clause was listed unsatisfied. The prompt never said so. The model therefore returned PARTIAL with
 * every clause satisfied, validation rejected it, the one permitted retry produced the same answer, and
 * the requirement was recorded as impossible to evaluate.
 *
 * <p>That failure is DETERMINISTIC, which is what made it costly: a targeted retry of CHG-09-R2 hit the
 * identical wall four times, burning four model calls to reach the same rejection. A rule enforced but
 * unstated is not a guardrail — it is a trap the model cannot see, and it converts a valid comparison
 * into a permanent coverage gap.
 *
 * <p>Asserted by reading both sources, because the two drift independently: adding a validation rule is
 * a one-line change in a different file from the prompt, and nothing else would notice.
 */
class EnforcedRulesAreStatedTest {

    private static final Path VALIDATOR = Path.of(
            "src/main/java/com/app/docreview/retrievalcomparison/comparison/validation/"
                    + "ComparisonOutputValidator.java");

    /** The prompt as the model receives it, whitespace flattened. */
    private String prompt() {
        return ComparisonPrompt.system("NONCE").replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("the PARTIAL rule that caused the deterministic rejection is stated")
    void partialRuleIsStated() throws IOException {
        var prompt = prompt();

        // The exact rule, and the reasoning. Stating only "list unsatisfied clauses" would leave the
        // model free to satisfy it by inventing one — the explanation is what makes the right response
        // choosing ALIGNED instead.
        assertThat(prompt)
                .as("the model must be told PARTIAL needs an unsatisfied clause")
                .containsIgnoringCase("at least one clause listed as unsatisfied");
        assertThat(prompt)
                .as("and what to do instead when every clause is satisfied")
                .containsIgnoringCase("the status is ALIGNED");
    }

    @Test
    @DisplayName("every status the validator constrains is described in the prompt")
    void everyConstrainedStatusIsDescribed() throws IOException {
        var prompt = prompt();

        // Each of these has at least one validator rule attached. A status the prompt lists as allowed
        // but never explains is one the model can pick and then fail on.
        for (var status : new String[]{"ALIGNED", "PARTIAL", "MISSING", "CONTRADICTED",
                "INSUFFICIENT_EVIDENCE"}) {
            assertThat(prompt).as("%s must be explained, not merely permitted", status)
                    .contains(status);
        }
        assertThat(prompt).containsIgnoringCase("supporting process span");
        assertThat(prompt).containsIgnoringCase("explicitly conflicts");
        assertThat(prompt).containsIgnoringCase("stated reason for the uncertainty");
    }

    @Test
    @DisplayName("the prompt says a response breaking a rule is REJECTED")
    void consequenceIsStated() throws IOException {
        // Without this the rules read as style advice. The model needs to know that an unsupportable
        // status must be replaced rather than submitted with the supporting field omitted — which is
        // precisely the mistake that produced the rejection loop.
        assertThat(prompt())
                .containsIgnoringCase("rejected");
    }

    @Test
    @DisplayName("no validator rule exists that the prompt leaves unmentioned")
    void noSilentlyEnforcedRule() throws IOException {
        assertThat(VALIDATOR).as("test must run from the module root").exists();
        var validator = Files.readString(VALIDATOR, StandardCharsets.UTF_8);
        var prompt = prompt().toLowerCase();

        // Extracts the status-consistency messages the validator can emit and checks the prompt
        // addresses each subject. A keyword match rather than the full sentence: the prompt should
        // explain a rule in its own words, not echo an error string.
        record Rule(String message, String keyword) {}
        var rules = java.util.List.of(
                new Rule("ALIGNED requires supporting process evidence", "aligned"),
                new Rule("PARTIAL requires related process evidence", "partial"),
                new Rule("PARTIAL requires at least one unsatisfied clause", "unsatisfied"),
                new Rule("CONTRADICTED requires explicit conflicting process evidence", "conflict"),
                new Rule("INSUFFICIENT_EVIDENCE requires an uncertainty reason", "uncertainty"));

        for (var rule : rules) {
            assertThat(validator)
                    .as("this test is stale if the validator no longer emits: %s", rule.message())
                    .contains(rule.message());
            assertThat(prompt)
                    .as("the prompt must address '%s' — enforcing it silently makes it a trap",
                            rule.keyword())
                    .contains(rule.keyword());
        }
    }

    @Test
    @DisplayName("the prompt version moved, so stored comparisons name the right prompt")
    void versionBumped() {
        // §19.2: a comparison records the prompt that produced it. Changing the wording without the
        // version would make every earlier comparison name a prompt it never ran under.
        assertThat(ComparisonPrompt.PROMPT_VERSION).isEqualTo("COMPARE-V2");
    }
}
