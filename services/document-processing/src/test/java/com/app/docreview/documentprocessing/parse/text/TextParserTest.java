package com.app.docreview.documentprocessing.parse.text;

import com.app.docreview.contracts.document.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-text and Markdown parsing. Architecture §8.2, §11.2.
 *
 * <p>The assertions concentrate on SECTION PATH, because that is what the rest of the pipeline
 * depends on: §12.2 uses heading context to decide whether a clause is a mandatory control or
 * guidance, so a parser that flattens structure silently degrades every downstream verdict.
 */
class TextParserTest {

    private final TextParser parser = new TextParser();

    private TextParser.ParsedText parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8), "DOC-V1");
    }

    @Nested
    @DisplayName("numbered headings (§11.2)")
    class NumberedHeadings {

        @Test
        @DisplayName("a numbered section title nests clauses beneath it")
        void nestsUnderSection() {
            var result = parse("""
                    3. Account Provisioning

                    Access requests are raised in the service desk tool.
                    """);

            var paragraph = result.nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .findFirst()
                    .orElseThrow();

            assertThat(paragraph.sectionPath()).containsExactly("3. Account Provisioning");
        }

        @Test
        @DisplayName("depth comes from the numbering, so 5.1 nests under 5")
        void depthFromNumbering() {
            var result = parse("""
                    5. Access Review

                    5.1 Quarterly Review

                    The data owner performs the review.
                    """);

            var paragraph = result.nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .findFirst()
                    .orElseThrow();

            assertThat(paragraph.sectionPath())
                    .containsExactly("5. Access Review", "5.1 Quarterly Review");
        }

        @Test
        @DisplayName("a sibling section replaces its predecessor rather than nesting")
        void siblingsDoNotNest() {
            var result = parse("""
                    3. Provisioning

                    4. Authentication

                    Multi-factor authentication is enforced.
                    """);

            var paragraph = result.nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .findFirst()
                    .orElseThrow();

            // Without popping equal-depth levels the path would grow to ["3.", "4."] and every later
            // clause would claim to live inside section 3 as well.
            assertThat(paragraph.sectionPath()).containsExactly("4. Authentication");
        }

        @Test
        @DisplayName("a numbered SENTENCE is a clause, not a section title")
        void numberedSentenceIsAClause() {
            var result = parse("""
                    3. Account Provisioning

                    3.1 Access must be approved by the data owner before the account is created.
                    """);

            // The requirement itself must be a PARAGRAPH: promoting it to a heading would make the
            // obligation a container rather than something comparable (§12.2).
            var clause = result.nodes().stream()
                    .filter(n -> n.text().startsWith("3.1 Access must be approved"))
                    .findFirst()
                    .orElseThrow();

            assertThat(clause.nodeType()).isEqualTo(NodeType.PARAGRAPH);
            assertThat(clause.sectionPath()).containsExactly("3. Account Provisioning");
        }
    }

    @Nested
    @DisplayName("markdown")
    class Markdown {

        @Test
        @DisplayName("# levels set the section depth")
        void markdownDepth() {
            var result = parse("""
                    # Access Control Standard

                    ## Authentication

                    Passwords must be at least twelve characters.
                    """);

            var paragraph = result.nodes().stream()
                    .filter(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .findFirst()
                    .orElseThrow();

            assertThat(paragraph.sectionPath())
                    .containsExactly("Access Control Standard", "Authentication");
        }

        @Test
        @DisplayName("bullets become LIST_ITEM nodes")
        void bulletsAreListItems() {
            var result = parse("""
                    ## Requirements

                    - Access must be approved.
                    - Passwords must be rotated.
                    """);

            assertThat(result.nodes())
                    .filteredOn(n -> n.nodeType() == NodeType.LIST_ITEM)
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("paragraph assembly")
    class Paragraphs {

        @Test
        @DisplayName("wrapped lines join into one node")
        void wrappedLinesJoin() {
            var result = parse("""
                    Access must be approved by the data owner
                    before the account is created.
                    """);

            // One requirement split across two lines is still ONE obligation. Emitting a node per
            // line would fragment it into pieces too small to cite (§11.3).
            assertThat(result.nodes())
                    .filteredOn(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .hasSize(1);
            assertThat(result.nodes().get(0).text())
                    .isEqualTo("Access must be approved by the data owner before the account is created.");
        }

        @Test
        @DisplayName("a blank line separates paragraphs")
        void blankLineSeparates() {
            var result = parse("""
                    First requirement.

                    Second requirement.
                    """);

            assertThat(result.nodes())
                    .filteredOn(n -> n.nodeType() == NodeType.PARAGRAPH)
                    .hasSize(2);
        }

        @Test
        @DisplayName("CRLF endings do not leak into node text")
        void crlfNormalised() {
            // A Windows-authored policy document would otherwise carry \\r inside every node, which
            // changes the text hash and breaks the mutation check in §11.3.
            var result = parse("Access must be approved.\r\n\r\nPasswords must rotate.\r\n");

            assertThat(result.nodes()).allSatisfy(n -> assertThat(n.text()).doesNotContain("\r"));
        }
    }

    @Nested
    @DisplayName("pipeline contract")
    class PipelineContract {

        @Test
        @DisplayName("reassembled text contains every node verbatim, in order")
        void textContainsNodesInOrder() {
            var result = parse("""
                    4. Authentication

                    4.1 Multi-factor authentication must be enforced.

                    4.2 Passwords must be at least twelve characters.
                    """);

            // Span offsets are resolved by searching this text (DocumentProcessingJobRunner
            // .charOffsets). A node whose text is absent, or out of order, produces a span pointing
            // at the wrong passage — evidence that cites the wrong sentence.
            int cursor = 0;
            for (var node : result.nodes()) {
                int found = result.text().indexOf(node.text(), cursor);
                assertThat(found)
                        .as("node %s must appear at or after offset %s", node.ordinal(), cursor)
                        .isGreaterThanOrEqualTo(0);
                cursor = found;
            }
        }

        @Test
        @DisplayName("every node carries a text hash and an ascending ordinal")
        void nodesAreHashedAndOrdered() {
            var result = parse("""
                    First requirement.

                    Second requirement.
                    """);

            for (int i = 0; i < result.nodes().size(); i++) {
                var node = result.nodes().get(i);
                assertThat(node.textHash()).isNotBlank();
                assertThat(node.ordinal()).isEqualTo(i);
                // No geometry in a text file; invented boxes would place highlights arbitrarily.
                assertThat(node.boundingBoxes()).isEmpty();
                assertThat(node.page()).isNull();
            }
        }

        @Test
        @DisplayName("an empty document yields no nodes rather than throwing")
        void emptyDocument() {
            // §17.5 depends on this: a document that parses to nothing must be visible as "no spans"
            // so a comparison reaches INSUFFICIENT_EVIDENCE rather than reporting every requirement
            // MISSING.
            assertThat(parse("   \n\n  ").nodes()).isEmpty();
        }
    }
}
