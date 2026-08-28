package com.app.docreview.retrievalcomparison.indexing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selection profile is built from metadata, not just the title. Architecture §15.1.
 *
 * <p>{@code StandardIndexingJobRunner} passed {@code null, null, null, null, List.of()} for every
 * descriptive field, so each profile vector was embedded from the title alone. Two versions of
 * "Technology Change Management Standard" therefore produced nearly identical vectors, and automatic
 * selection had nothing to distinguish them — a live index of 36 standards showed empty tags and no
 * category on every single record.
 *
 * <p><strong>Why a source assertion.</strong> What broke was an argument list, and the failure mode is
 * a call that compiles and runs while discarding its inputs. Exercising the runner would need a vector
 * store, an embedding gateway, a span reader, and two repositories — the mocks would pass whatever the
 * runner asked for and prove nothing about whether it asks for anything at all.
 */
class StandardProfileTest {

    private static final Path RUNNER = Path.of(
            "src/main/java/com/app/docreview/retrievalcomparison/jobs/StandardIndexingJobRunner.java");

    private String indexCall() throws IOException {
        assertThat(RUNNER).as("test must run from the module root").exists();
        var source = Files.readString(RUNNER, StandardCharsets.UTF_8);
        // Spans the StandardMetadata construction AND the call, because the fields now travel in a
        // record rather than as positional arguments. Checking only the call would pass while every
        // field in the record was null.
        int start = source.indexOf("var metadata = ");
        assertThat(start).as("the metadata record must be built").isGreaterThan(0);
        int end = source.indexOf("indexRequirements(requirements", start);
        assertThat(end).as("requirements must be indexed with that metadata").isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("the descriptive fields are passed, not hardcoded null")
    void metadataReachesTheProfile() throws IOException {
        var call = indexCall();

        // The three fields the profile string is actually built from (see indexStandardProfile).
        assertThat(call)
                .as("description feeds the matching vector; dropping it reverts the bug")
                .contains("getStandardDescription()");
        assertThat(call)
                .as("category is the most discriminating field for selection")
                .contains("getStandardCategory()");
        assertThat(call)
                .as("tags feed the matching vector")
                .contains("getStandardTags()");
    }

    @Test
    @DisplayName("owner is deliberately NOT part of the profile")
    void ownerIsExcluded() throws IOException {
        // A person's name says nothing about what a standard covers, and including it would let two
        // unrelated standards match because the same person owns them.
        assertThat(indexCall()).doesNotContain("getStandardOwner()");
    }

    @Test
    @DisplayName("the original all-null argument list cannot come back")
    void noSilentlyDiscardedFields() throws IOException {
        // The defect was literally `null, null, null, null, List.of()`. Counting nulls turned out to
        // be the wrong assertion — a legitimate `getStandardTags() == null ? ...` guard contains one
        // too — so this pins the DEFECT ITSELF rather than a proxy for it.
        var call = indexCall().replaceAll("\\s+", " ");

        assertThat(call)
                .as("every descriptive field hardcoded to null is the exact bug being prevented")
                .doesNotContain("null, null, null, null");
        assertThat(call)
                .as("an empty tag literal in place of the real tags is the same bug")
                .doesNotContain("null, List.of()");
        // Phase 1: the two ranking dimensions must reach the payload too, or candidates cannot be
        // ranked by them however well the standard is labelled.
        assertThat(call).contains("getStandardFamily()");
        assertThat(call).contains("getStandardDocumentType()");
    }
}
