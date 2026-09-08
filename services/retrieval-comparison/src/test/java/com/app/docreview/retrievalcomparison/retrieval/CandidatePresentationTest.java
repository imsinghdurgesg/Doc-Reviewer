package com.app.docreview.retrievalcomparison.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How candidates and the inferred context are PRESENTED. Architecture §15.1, §15.5.
 *
 * <p>Both defects pinned here were found by calling the live endpoint, not by reading the code — each
 * produced a plausible-looking response that misrepresented what had happened.
 *
 * <p><strong>Why source assertions.</strong> The controller's collaborators are a vector store, an
 * embedding gateway, a span reader, and a model gateway. Standing all four up to observe two integers
 * and a string would test the harness; what broke was a constant and a branch, and that is what these
 * read. The behavioural companions live in {@link ContextRankingTest}, which exercises the real ranking
 * path through {@code StandardSelectionService}.
 */
class CandidatePresentationTest {

    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/app/docreview/retrievalcomparison/retrieval/document/web/"
                    + "StandardCandidateController.java");

    private String source() throws IOException {
        assertThat(CONTROLLER).as("test must run from the module root").exists();
        return Files.readString(CONTROLLER, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("preselection is bounded independently of how many are shown")
    class Preselection {

    @Test
    @DisplayName("preselection is read from configuration, not hard-coded")
    void preselectionComesFromConfiguration() throws IOException {
        // The constant this asserted on is GONE. Preselect and auto-select answered the same
        // reviewer-facing question — how many standards are proposed — with two different numbers, 3
        // and 1, so the screen could pre-check three while automatic selection took one and no setting
        // explained the difference. Both now read one Admin value.
        //
        // What still needs protecting is that no literal creeps back: a hard-coded count would ignore
        // the setting silently, and an administrator changing it would see no effect.
        var source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);

        assertThat(source)
                .as("the preselect count must come from configuration")
                .contains("reviewLimits.current().recommendedStandards()");

        assertThat(source)
                .as("the removed constant must not be reintroduced")
                .doesNotContain("MAX_PRESELECTED_STANDARDS =");
    }


        @Test
        @DisplayName("rank() is called with the preselection cap, not the display limit")
        void rankUsesPreselectionCap() throws IOException {
            // The two numbers must not be the same argument again. `rank`'s maxResults controls how
            // many are SELECTED, so passing the display limit is what conflated them.
            var src = source().replaceAll("\\s+", " ");

            assertThat(src)
                    .as("selection count must come from the preselection cap")
                    .contains("selection.rank(hits, preselected, context)");
            assertThat(src)
                    .as("the display limit must still bound what is returned")
                    .contains("candidates.subList(0, shown)");
        }
    }

    @Nested
    @DisplayName("confidence tiers")
    class Tiers {

        @Test
        @DisplayName("zero confidence is NONE, not LOW")
        void zeroConfidenceIsNone() throws IOException {
            // Observed live: the model returned documentType="Procedure" with no `confidence` field,
            // which clamps to 0.0 -- and the tier came back LOW, so the UI invited the reviewer to
            // confirm a value the model had expressed no confidence in at all.
            var src = source().replaceAll("\\s+", " ");

            assertThat(src)
                    .as("a 0.0 confidence must not be presented as a weak suggestion")
                    .contains("if (inferred.confidence() <= 0.0) { return \"NONE\"; }");
        }

        @Test
        @DisplayName("the tier thresholds stay ordered")
        void thresholdsAreOrdered() throws IOException {
            var src = source();
            var high = Pattern.compile("HIGH_CONFIDENCE\\s*=\\s*([0-9.]+)").matcher(src);
            var low = Pattern.compile("LOW_CONFIDENCE\\s*=\\s*([0-9.]+)").matcher(src);

            assertThat(high.find()).isTrue();
            assertThat(low.find()).isTrue();
            // A LOW above HIGH would make MEDIUM unreachable and silently collapse the tiers.
            assertThat(Double.parseDouble(low.group(1)))
                    .isLessThan(Double.parseDouble(high.group(1)));
        }
    }
}
