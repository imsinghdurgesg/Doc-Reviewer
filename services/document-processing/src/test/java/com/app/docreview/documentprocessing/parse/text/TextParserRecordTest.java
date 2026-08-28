package com.app.docreview.documentprocessing.parse.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Record-oriented content: CSV rows and JSON members. Architecture §8.2, §11.2.
 *
 * <p><strong>The regression this exists for.</strong> The paragraph accumulator joins consecutive
 * non-blank lines, which is right for wrapped prose and catastrophic for records: CSV and JSON have
 * no blank lines, so an entire file collapsed into ONE node. A CSV of fifteen controls became a
 * single unciteable span — retrieval could only return "the whole document", and the extractor saw
 * one blob where fifteen requirements should have been.
 */
class TextParserRecordTest {

    private final TextParser parser = new TextParser();

    private TextParser.ParsedText parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8), "DOC-V1");
    }

    @Nested
    @DisplayName("CSV")
    class Csv {

        private static final String CONTROLS = """
                Control,Requirement,Owner
                2.1,All production changes must be recorded before implementation,Change Manager
                2.2,A change must be approved by someone who did not implement it,Release Manager
                3.1,A security assessment must be completed before contract signature,Security Lead
                """;

        @Test
        @DisplayName("each row becomes its own citable node")
        void rowsAreSeparateNodes() {
            var result = parse(CONTROLS);

            // Header plus three data rows. Previously: 1.
            assertThat(result.nodes()).hasSize(4);
        }

        @Test
        @DisplayName("a row's text is not merged with its neighbours")
        void rowsAreNotJoined() {
            var nodes = parse(CONTROLS).nodes();

            assertThat(nodes).anySatisfy(n ->
                    assertThat(n.text()).startsWith("2.1,").doesNotContain("2.2,"));
            assertThat(nodes).anySatisfy(n ->
                    assertThat(n.text()).startsWith("3.1,").doesNotContain("2.2,"));
        }

        @Test
        @DisplayName("tab-separated content is handled the same way")
        void tabSeparated() {
            var result = parse("""
                    Control\tRequirement
                    2.1\tChanges must be recorded
                    2.2\tChanges must be approved
                    3.1\tAssessments must be completed
                    """);

            assertThat(result.nodes()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("JSON")
    class Json {

        @Test
        @DisplayName("members become separate nodes rather than one blob")
        void membersAreSeparateNodes() {
            var result = parse("""
                    {
                      "recording": "All production changes must be recorded.",
                      "approval": "Changes must be approved independently.",
                      "emergency": "Emergency changes must be reviewed."
                    }
                    """);

            // Previously the whole object was a single node.
            assertThat(result.nodes()).hasSizeGreaterThan(1);
            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text()).contains("recording").doesNotContain("approval"));
        }
    }

    @Nested
    @DisplayName("prose must NOT be treated as records")
    class ProseIsUnaffected {

        @Test
        @DisplayName("a wrapped sentence stays one paragraph")
        void wrappedProseStaysJoined() {
            // The failure mode in the other direction: splitting per line would fragment every
            // requirement into pieces that cite nothing.
            var result = parse("""
                    3. Approval

                    Changes must be approved before deployment to production, and the
                    approver must be independent of the engineer who implemented the
                    change.
                    """);

            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text())
                            .contains("Changes must be approved")
                            .contains("independent of the engineer"));
        }

        @Test
        @DisplayName("prose containing commas is not mistaken for CSV")
        void commaProseIsNotCsv() {
            var result = parse("""
                    Changes must be recorded, approved, and tested before release.
                    Evidence must be retained, indexed, and available for audit.
                    Rollback must be documented, rehearsed, and verified.
                    """);

            // Three comma-heavy lines with no blank separator — but it is prose, so the accumulator
            // must still join them rather than emitting one node per line.
            assertThat(result.nodes()).hasSize(1);
        }

        @Test
        @DisplayName("a very short delimited file is left as prose")
        void tooSmallToClassify() {
            // Below MIN_RECORD_LINES: not enough evidence, and being wrong is costly.
            var result = parse("a,b,c\nd,e,f\n");

            assertThat(result.nodes()).hasSize(1);
        }

        @Test
        @DisplayName("markdown headings still structure a document")
        void markdownUnaffected() {
            var result = parse("""
                    # Change Management

                    ## Approval

                    Changes must be approved before deployment.
                    """);

            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.sectionPath()).contains("Change Management", "Approval"));
        }
    }
}
