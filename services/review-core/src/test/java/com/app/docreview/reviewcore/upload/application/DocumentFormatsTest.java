package com.app.docreview.reviewcore.upload.application;

import com.app.docreview.reviewcore.shared.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accepted upload formats. Architecture §8.2.
 *
 * <p>The list must match what a parser can actually READ, not what can be stored. A format accepted
 * here but unparseable downstream produces a document that renders and has no citable evidence —
 * which surfaces as a system fault rather than an unsupported file.
 */
class DocumentFormatsTest {

    @Test
    @DisplayName("every parseable format is accepted")
    void acceptsSupported() {
        for (String name : new String[] {
                "standard.pdf", "process.docx", "notes.txt",
                "readme.md", "readme.markdown", "controls.csv", "config.json"}) {
            assertThat(DocumentFormats.isSupported(name))
                    .as("%s must be accepted", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("legacy binary .doc is rejected, because no parser can read it")
    void rejectsLegacyDoc() {
        // .doc needs POI's HWPF (poi-scratchpad); only poi-ooxml is on the classpath, so the OOXML
        // parser fails on a non-ZIP body. It previously passed validation and yielded zero spans.
        assertThat(DocumentFormats.isSupported("procedure.doc")).isFalse();

        assertThatThrownBy(() -> DocumentFormats.requireSupported("procedure.doc"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    @DisplayName(".docx is still accepted and is not confused with .doc")
    void docxStillAccepted() {
        // Guards the obvious mistake in the rejection above: a suffix check for ".doc" would also
        // match ".docx" if written carelessly.
        assertThatCode(() -> DocumentFormats.requireSupported("procedure.docx"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown extensions and null are rejected")
    void rejectsUnknown() {
        assertThat(DocumentFormats.isSupported("archive.zip")).isFalse();
        assertThat(DocumentFormats.isSupported("image.png")).isFalse();
        assertThat(DocumentFormats.isSupported(null)).isFalse();
    }

    @Test
    @DisplayName("extension matching is case-insensitive")
    void caseInsensitive() {
        assertThat(DocumentFormats.isSupported("STANDARD.PDF")).isTrue();
        assertThat(DocumentFormats.isSupported("Process.DocX")).isTrue();
    }
}
