package com.app.docreview.documentprocessing.canonical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Running header and footer detection. Architecture §11.2.
 *
 * <p>This matters because an undetected header is indexed once per page and, containing the document
 * title, ranks highly for almost every query about that document.
 */
class RepeatedContentDetectorTest {

    @Test
    @DisplayName("a line on most pages is detected as running content")
    void detectsRunningHeader() {
        var pages = List.of(
                List.of("Change Management Standard", "1 Purpose", "This standard applies."),
                List.of("Change Management Standard", "2 Scope", "All production systems."),
                List.of("Change Management Standard", "3 Roles", "The owner approves."),
                List.of("Change Management Standard", "4 Approval", "An independent reviewer."));

        assertThat(RepeatedContentDetector.detect(pages))
                .contains("change management standard");
    }

    @Test
    @DisplayName("page numbers are normalized so varying footers still match")
    void normalizesPageNumbers() {
        var pages = List.of(
                List.of("body one", "Page 1 of 18"),
                List.of("body two", "Page 2 of 18"),
                List.of("body three", "Page 3 of 18"),
                List.of("body four", "Page 4 of 18"));

        // Without digit normalization each footer is unique and none is detected — which is the
        // most common real form of this content.
        assertThat(RepeatedContentDetector.detect(pages))
                .contains("page # of #");
    }

    @Test
    @DisplayName("real content appearing on a minority of pages is not suppressed")
    void doesNotSuppressBodyContent() {
        var pages = List.of(
                List.of("hdr", "An independent reviewer must approve the change."),
                List.of("hdr", "Unrelated body text."),
                List.of("hdr", "More unrelated text."),
                List.of("hdr", "Further text."));

        assertThat(RepeatedContentDetector.detect(pages))
                .as("suppressing a requirement would make it unfindable and unreportable")
                .doesNotContain("an independent reviewer must approve the change.");
    }

    @Test
    @DisplayName("short documents have no repeated content by definition")
    void shortDocumentsAreExempt() {
        var pages = List.of(List.of("Title", "body"), List.of("Title", "body"));
        // In a two-page document, a line on both pages is not evidence of a running header.
        assertThat(RepeatedContentDetector.detect(pages)).isEmpty();
    }

    @Test
    @DisplayName("repetition within one page is not running content")
    void repetitionOnOnePageIsNotAHeader() {
        var pages = List.of(
                List.of("emphasis", "emphasis", "emphasis"),
                List.of("other"), List.of("other two"), List.of("other three"));
        assertThat(RepeatedContentDetector.detect(pages)).doesNotContain("emphasis");
    }
}
