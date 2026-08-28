package com.app.docreview.documentprocessing.parse.csv;

import com.app.docreview.contracts.document.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delimited-data parsing. Architecture §8.2, §11.2.
 *
 * <p>Assertions concentrate on ROW SEPARATION and rendered text, because those are what make a row
 * citable. A CSV parsed as prose collapsed into one node, so retrieval could only return "the whole
 * document" and a finding could never point at the control it was about.
 */
class CsvParserTest {

    private final CsvParser parser = new CsvParser();

    private CsvParser.ParsedCsv parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8), "DOC-V1");
    }

    private static final String CONTROLS = """
            Control,Requirement,Owner
            2.1,All production changes must be recorded,Change Manager
            2.2,A change must be approved independently,Release Manager
            3.1,A security assessment must be completed,Security Lead
            """;

    @Nested
    @DisplayName("rows become citable records")
    class Rows {

        @Test
        @DisplayName("one node per data row, plus the table itself")
        void rowsAreSeparateNodes() {
            var result = parse(CONTROLS);

            assertThat(result.nodes()).hasSize(4);   // 1 TABLE + 3 TABLE_ROW
            assertThat(result.nodes().stream().filter(n -> n.nodeType() == NodeType.TABLE_ROW))
                    .hasSize(3);
            assertThat(result.nodes().get(0).nodeType()).isEqualTo(NodeType.TABLE);
        }

        @Test
        @DisplayName("a row is rendered with its column names")
        void rowCarriesHeaderLabels() {
            var rows = parse(CONTROLS).nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.TABLE_ROW).toList();

            // "2.1" alone carries no obligation; the labelled form states one.
            assertThat(rows.get(0).text())
                    .isEqualTo("Control: 2.1 | Requirement: All production changes must be recorded "
                            + "| Owner: Change Manager");
        }

        @Test
        @DisplayName("all rows share the header as their section, so they group as one table")
        void rowsShareSection() {
            var rows = parse(CONTROLS).nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.TABLE_ROW).toList();

            // One section for the table means CandidateDetector emits one candidate for it rather
            // than one per row, which would be a model call each.
            assertThat(rows).allSatisfy(r ->
                    assertThat(r.sectionPath()).containsExactly("Control, Requirement, Owner"));
        }

        @Test
        @DisplayName("the reassembled text contains every node verbatim and in order")
        void textIsSearchable() {
            var result = parse(CONTROLS);

            // Span offsets are resolved by searching this text; a node missing from it gets offset 0.
            int previous = -1;
            for (var node : result.nodes()) {
                int at = result.text().indexOf(node.text());
                assertThat(at).as("%s must appear in the document text", node.text()).isNotNegative();
                assertThat(at).isGreaterThan(previous);
                previous = at;
            }
        }
    }

    @Nested
    @DisplayName("delimiters and quoting")
    class Formats {

        @Test
        @DisplayName("tab-separated data is detected")
        void tabs() {
            var result = parse("""
                    Control\tRequirement
                    2.1\tChanges must be recorded
                    2.2\tChanges must be approved
                    """);

            assertThat(result.nodes().stream().filter(n -> n.nodeType() == NodeType.TABLE_ROW))
                    .hasSize(2);
        }

        @Test
        @DisplayName("a quoted field containing the delimiter stays one field")
        void quotedDelimiter() {
            var result = parse("""
                    Control,Requirement
                    2.1,"Changes must be recorded, approved, and tested"
                    2.2,"Approval must be independent"
                    """);

            var rows = result.nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.TABLE_ROW).toList();
            // The commas inside the quotes must not have split the cell into three.
            assertThat(rows.get(0).text())
                    .isEqualTo("Control: 2.1 | Requirement: Changes must be recorded, approved, "
                            + "and tested");
        }

        @Test
        @DisplayName("a doubled quote is a literal quote")
        void escapedQuote() {
            var result = parse("""
                    Term,Definition
                    change,"any ""production"" modification"
                    owner,"the accountable party"
                    """);

            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text()).contains("any \"production\" modification"));
        }

        @Test
        @DisplayName("a quoted newline keeps a multi-line cell together")
        void multilineCell() {
            var result = parse("Control,Requirement\n2.1,\"first line\nsecond line\"\n2.2,other\n");

            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text()).contains("first line").contains("second line"));
        }
    }

    @Nested
    @DisplayName("header handling")
    class Headers {

        @Test
        @DisplayName("a data-only file still yields one node per row")
        void noHeader() {
            // Every row ends like a sentence, so none of them is a header.
            var result = parse("""
                    2.1,Changes must be recorded before implementation.
                    2.2,Approval must be independent of the implementer.
                    3.1,Assessments must be completed before signature.
                    """);

            // No TABLE node, because there is no header to title it.
            assertThat(result.nodes()).hasSize(3);
            assertThat(result.nodes()).allSatisfy(n ->
                    assertThat(n.nodeType()).isEqualTo(NodeType.TABLE_ROW));
        }

        @Test
        @DisplayName("an empty cell is omitted rather than rendered as an empty claim")
        void emptyCellsOmitted() {
            var result = parse("""
                    Control,Requirement,Owner
                    2.1,Changes must be recorded,
                    """);

            // "Owner: " would assert an absent value.
            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text()).doesNotContain("Owner:"));
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("empty content yields no nodes rather than throwing")
        void empty() {
            assertThat(parse("").nodes()).isEmpty();
            assertThat(parse("\n\n\n").nodes()).isEmpty();
        }

        @Test
        @DisplayName("blank rows are skipped")
        void blankRows() {
            var result = parse("Control,Requirement\n2.1,Recorded\n\n\n2.2,Approved\n");

            assertThat(result.nodes().stream().filter(n -> n.nodeType() == NodeType.TABLE_ROW))
                    .hasSize(2);
        }
    }
}
