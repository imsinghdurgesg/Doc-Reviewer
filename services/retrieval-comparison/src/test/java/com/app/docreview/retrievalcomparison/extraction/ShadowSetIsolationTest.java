package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument.ValidationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shadow requirement set cannot reach a comparison. Architecture §12.9, §19.2.
 *
 * <p><strong>What goes wrong if this breaks.</strong> A comparison evaluates against the newest
 * VALIDATED set. An evaluation run saved as VALIDATED would therefore become the live set the moment it
 * finished — silently replacing 113 human-reviewed condition classifications with a fresh model guess.
 * Nothing would fail, no log would say so, and the next comparison would simply use different roles.
 *
 * <p>That is a deployment disguised as a measurement, so it gets structural tests rather than trust.
 */
class ShadowSetIsolationTest {

    @Test
    @DisplayName("only VALIDATED is comparable")
    void onlyValidatedIsComparable() {
        assertThat(ValidationStatus.VALIDATED.comparable()).isTrue();
        assertThat(ValidationStatus.SHADOW.comparable()).isFalse();
        assertThat(ValidationStatus.NEEDS_REVIEW.comparable()).isFalse();
    }

    @Test
    @DisplayName("every status is accounted for, so a new one defaults to unusable")
    void newStatusesDefaultToUnusable() {
        // A positive test on VALIDATED means adding a status cannot accidentally make it live. If this
        // ever becomes `!= SHADOW`, a future status silently becomes comparable — which is the whole
        // failure mode being guarded.
        for (var status : ValidationStatus.values()) {
            if (status != ValidationStatus.VALIDATED) {
                assertThat(status.comparable())
                        .as("%s must not be comparable unless deliberately made so", status)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("shadow extraction never indexes or embeds")
    void shadowExtractionDoesNotTouchTheCorpus() throws IOException {
        // THE SECOND GUARD, asserted on source because it is an ABSENCE.
        //
        // The normal indexing pipeline extracts and then embeds into the live standards corpus,
        // deleting the version's previous vectors first. A shadow run routed through it would replace
        // the reviewed set's vectors with an unreviewed extraction's — and every behavioural test would
        // still pass, because the extraction itself was correct.
        var source = Files.readString(Path.of(
                        "src/main/java/com/app/docreview/retrievalcomparison/extraction/web/"
                                + "ShadowExtractionController.java"), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> {
                    String t = line.strip();
                    return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
                })
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(source).doesNotContain("VectorIndexingService");
        assertThat(source).doesNotContain("indexRequirements");
        assertThat(source).doesNotContain("EmbeddingGateway");
        assertThat(source).doesNotContain("deleteStandardVectors");
        // And it must call the shadow path, not the publishing one.
        assertThat(source).contains("extractShadow");
    }

    @Test
    @DisplayName("the comparison selection query filters on VALIDATED explicitly")
    void comparisonSelectsOnlyValidatedSets() throws IOException {
        var runner = Files.readString(Path.of(
                        "src/main/java/com/app/docreview/retrievalcomparison/jobs/"
                                + "ComparisonJobRunner.java"), StandardCharsets.UTF_8);

        // Pinned as a literal because this is the single line separating "evaluation" from "live". A
        // refactor to an unfiltered "newest set" lookup would make every shadow run a deployment.
        assertThat(runner)
                .as("a comparison must select the newest VALIDATED set, never merely the newest")
                .contains("findFirstByStandardVersionIdAndValidationStatusOrderByCreatedAtDesc");
        assertThat(runner).contains("ValidationStatus.VALIDATED");
    }
}
