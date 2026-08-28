package com.app.docreview.retrievalcomparison.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorded prompt version covers the administered guidance. Architecture §19.2.
 *
 * <p>A comparison stores {@code promptVersion} so a finding can be explained months later. That
 * version described only the prompt text in CODE, while an administrator can also append guidance
 * stored in the database — so two comparisons could both record {@code COMPARE-V1} having been given
 * materially different instructions. The field was decorative in exactly the situation where someone
 * needs to rely on it.
 */
class PromptVersionsTest {

    private static final String BASE = "COMPARE-V1";

    @Test
    @DisplayName("no guidance leaves the version untouched")
    void noGuidance() {
        // Load-bearing for existing records: every comparison already stored reads "COMPARE-V1", and
        // those runs genuinely had no guidance. Changing the format for them would make historical
        // records look like they had instructions that were never configured.
        assertThat(PromptVersions.forGuidance(BASE, null)).isEqualTo(BASE);
        assertThat(PromptVersions.forGuidance(BASE, "")).isEqualTo(BASE);
        assertThat(PromptVersions.forGuidance(BASE, "   \n  ")).isEqualTo(BASE);
    }

    @Test
    @DisplayName("guidance appends a digest")
    void guidanceAppendsDigest() {
        var version = PromptVersions.forGuidance(BASE, "Be strict about approval evidence.");

        assertThat(version).startsWith(BASE + "+");
        // Short enough to read in a UI or a log line. A full hash would make the field unusable.
        assertThat(version).hasSize(BASE.length() + 1 + 8);
    }

    @Test
    @DisplayName("the same guidance always yields the same version")
    void stableAcrossRuns() {
        // Without this, two runs under identical instructions would look different and the field
        // would report change where none happened.
        String first = PromptVersions.forGuidance(BASE, "Require named approvers.");
        String second = PromptVersions.forGuidance(BASE, "Require named approvers.");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("different guidance yields a different version")
    void changeIsVisible() {
        String before = PromptVersions.forGuidance(BASE, "Require named approvers.");
        String after = PromptVersions.forGuidance(BASE, "Require named approvers and a date.");

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("reformatting alone does not move the version")
    void whitespaceNormalised() {
        // An administrator who rewraps a paragraph has not changed what the model is told. A version
        // that moved for that reason would train people to ignore it.
        String tidy = PromptVersions.forGuidance(BASE, "Require named approvers. Check dates.");
        String rewrapped = PromptVersions.forGuidance(BASE,
                "  Require named approvers.\n   Check dates.  ");

        assertThat(tidy).isEqualTo(rewrapped);
    }

    @Test
    @DisplayName("a different base prompt is never conflated with another")
    void basePromptDistinguished() {
        // The three prompts share this helper. Same guidance under different prompts must not produce
        // the same version, or an extraction change would look like a comparison change.
        String comparison = PromptVersions.forGuidance("COMPARE-V1", "Be strict.");
        String extraction = PromptVersions.forGuidance("REQ-EXTRACT-V1", "Be strict.");

        assertThat(comparison).isNotEqualTo(extraction);
        assertThat(comparison).startsWith("COMPARE-V1+");
        assertThat(extraction).startsWith("REQ-EXTRACT-V1+");
    }

    @Test
    @DisplayName("the digest does not leak the guidance text")
    void digestIsOpaque() {
        // The version reaches logs and the browser. Guidance can name internal policies or people, so
        // the label must identify without disclosing.
        var version = PromptVersions.forGuidance(BASE, "Escalate to Jane Doe in Legal.");

        assertThat(version).doesNotContain("Jane", "Legal", "Escalate");
    }
}
