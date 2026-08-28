package com.app.docreview.retrievalcomparison.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-selection compares against exactly one standard. Architecture §15.1.
 *
 * <p>When a reviewer selects no standard the runner picks one itself. That limit was 3, and the
 * result was a worse review: 118 findings instead of 65, with control CHG-01 reported three times
 * from three different standards. A reviewer cannot tell whether that is three real gaps or one gap
 * counted three times, and the duplication says nothing about the document — it is an artefact of how
 * many standards were selected.
 *
 * <p><strong>Why a source assertion.</strong> The limit is a private constant on a service whose
 * collaborators are a vector store, a model gateway, and two repositories; constructing it to observe
 * one integer would test the harness rather than the decision. {@code StandardSelectionServiceTest}
 * already proves the service honours {@code maxResults=1} including on an exact tie — what is
 * unprotected is the CALLER's choice of that value, which is what this pins.
 */
class AutoSelectionLimitTest {

    private static final Path RUNNER = Path.of(
            "src/main/java/com/app/docreview/retrievalcomparison/jobs/ComparisonJobRunner.java");

    @Test
    @DisplayName("the auto-selection limit is 1")
    void limitIsOne() throws IOException {
        assertThat(RUNNER)
                .as("test must run from the module root to read the runner source")
                .exists();

        var source = Files.readString(RUNNER, StandardCharsets.UTF_8);
        var declaration = Pattern
                .compile("MAX_AUTO_SELECTED_STANDARDS\\s*=\\s*(\\d+)\\s*;")
                .matcher(source);

        assertThat(declaration.find())
                .as("MAX_AUTO_SELECTED_STANDARDS must exist; auto-selection is unbounded without it")
                .isTrue();

        assertThat(declaration.group(1))
                .as("auto-selection must compare against exactly one standard: several report the "
                        + "same gap once per standard, which reads as several distinct gaps")
                .isEqualTo("1");
    }
}
