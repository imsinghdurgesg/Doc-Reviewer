package com.app.docreview.documentprocessing.parse.pdf;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heading versus clause classification for PDFs. Architecture §11.2, §12.2.
 *
 * <p><strong>The regression this exists for.</strong> {@code CLAUSE_HEADING} matched any numbered
 * line under 80 characters, so a real control — "3.1 Changes must be approved before deployment." —
 * became a HEADING. Its text left the content stream for the section path, and because heading depth
 * pops the path to {@code depth-1}, a depth-2 sentence replaced the depth-2 title above it. Sections
 * collapsed into one group, {@code CandidateDetector} emitted a single candidate, and one model call
 * extracted one requirement from an entire standard.
 *
 * <p>Assertions concentrate on SECTION PATH and node type, because those are what the extraction
 * pipeline consumes. A requirement absorbed into a section path is never extracted, never evaluated,
 * and never reported as a gap — the failure is invisible downstream.
 */
class PdfParserHeadingTest {

    private final PdfParser parser = new PdfParser();

    /** Builds a real single-page text-layer PDF, one line per entry. */
    private static byte[] pdfOf(String... lines) throws IOException {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(font, 11);
                content.setLeading(15.5f);
                content.newLineAtOffset(62, 730);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private List<CanonicalNode> parse(String... lines) throws IOException {
        return parser.parse(pdfOf(lines), "STD-V1").nodes();
    }

    private CanonicalNode nodeStartingWith(List<CanonicalNode> nodes, String prefix) {
        return nodes.stream()
                .filter(n -> n.text() != null && n.text().strip().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node starting with: " + prefix));
    }

    @Nested
    @DisplayName("a numbered requirement sentence is a CLAUSE, not a heading")
    class NumberedSentences {

        @Test
        @DisplayName("a numbered line ending in a period stays a paragraph")
        void sentenceIsNotAHeading() throws IOException {
            var nodes = parse(
                    "3. Approval",
                    "3.1 Changes must be approved before deployment to production.");

            // The section title is a heading...
            assertThat(nodeStartingWith(nodes, "3. Approval").nodeType())
                    .isEqualTo(NodeType.HEADING);
            // ...but the control itself is content the extractor can see.
            assertThat(nodeStartingWith(nodes, "3.1 Changes").nodeType())
                    .isEqualTo(NodeType.PARAGRAPH);
        }

        @Test
        @DisplayName("a long numbered line with no period is still a clause")
        void tooManyWordsIsNotATitle() throws IOException {
            // No terminal punctuation, but far too many words to be a title.
            var nodes = parse(
                    "8. Authentication",
                    "8.1 Multi-factor authentication must be enforced for all remote access to production");

            assertThat(nodeStartingWith(nodes, "8.1 Multi-factor").nodeType())
                    .isEqualTo(NodeType.PARAGRAPH);
        }

        @Test
        @DisplayName("a clause does not enter the section path")
        void clauseDoesNotBecomeSectionContext() throws IOException {
            var nodes = parse(
                    "3. Approval",
                    "3.1 Changes must be approved before deployment to production.",
                    "Approval evidence is recorded in the change record.");

            // The trailing paragraph must sit under "3. Approval" — NOT under the 3.1 sentence.
            var trailing = nodeStartingWith(nodes, "Approval evidence");
            assertThat(trailing.sectionPath()).containsExactly("3. Approval");
        }

        @Test
        @DisplayName("sibling clauses do not collapse into one section")
        void siblingsStayDistinct() throws IOException {
            // The exact collapse shape: two depth-2 sentences under one depth-1 title. Before the
            // fix, each replaced the other on the path and everything nested beneath the last one.
            var nodes = parse(
                    "2. Change Control",
                    "2.1 All production changes must be recorded before implementation.",
                    "2.2 A change must be approved by someone who did not implement it.",
                    "2.3 Emergency changes must be reviewed within five business days.");

            for (String prefix : List.of("2.1 All", "2.2 A change", "2.3 Emergency")) {
                var node = nodeStartingWith(nodes, prefix);
                assertThat(node.nodeType())
                        .as("%s must be a clause", prefix)
                        .isEqualTo(NodeType.PARAGRAPH);
                assertThat(node.sectionPath())
                        .as("%s must sit directly under the section title", prefix)
                        .containsExactly("2. Change Control");
            }
        }
    }

    @Nested
    @DisplayName("genuine section titles still work")
    class SectionTitles {

        @Test
        @DisplayName("a short numbered title is a heading and nests its content")
        void shortTitleIsAHeading() throws IOException {
            var nodes = parse(
                    "4. Testing and Rollback",
                    "Changes are tested in a non-production environment first.");

            assertThat(nodeStartingWith(nodes, "4. Testing").nodeType())
                    .isEqualTo(NodeType.HEADING);
            assertThat(nodeStartingWith(nodes, "Changes are tested").sectionPath())
                    .containsExactly("4. Testing and Rollback");
        }

        @Test
        @DisplayName("nested titles build a hierarchical path")
        void nestedTitlesNest() throws IOException {
            var nodes = parse(
                    "3. Approval",
                    "3.1 Independent Review",
                    "The approver must be independent of the implementer.");

            assertThat(nodeStartingWith(nodes, "3.1 Independent").nodeType())
                    .isEqualTo(NodeType.HEADING);
            // Depth 2 nests under depth 1 rather than replacing it.
            assertThat(nodeStartingWith(nodes, "The approver").sectionPath())
                    .containsExactly("3. Approval", "3.1 Independent Review");
        }

        @Test
        @DisplayName("a title ending in a colon is treated as a clause, not a heading")
        void colonTerminatedIsNotATitle() throws IOException {
            // Terminal punctuation signals a lead-in sentence rather than a section title.
            var nodes = parse(
                    "5. Logging",
                    "5.1 The following must be logged:");

            assertThat(nodeStartingWith(nodes, "5.1 The following").nodeType())
                    .isEqualTo(NodeType.PARAGRAPH);
        }
    }

    @Nested
    @DisplayName("classification matches TextParser for the same content")
    class CrossParserConsistency {

        @Test
        @DisplayName("a clause is a clause whether the source was PDF or text")
        void agreesWithTextParser() throws IOException {
            String[] lines = {
                    "3. Approval",
                    "3.1 Changes must be approved before deployment to production.",
            };
            var fromPdf = parse(lines);
            var fromText = new com.app.docreview.documentprocessing.parse.text.TextParser()
                    .parse(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "STD-V1")
                    .nodes();

            // The two parsers feed the same grouping, so disagreeing here would extract a different
            // requirement set from the same document depending on its upload format.
            assertThat(nodeStartingWith(fromPdf, "3.1 Changes").nodeType())
                    .isEqualTo(nodeStartingWith(fromText, "3.1 Changes").nodeType())
                    .isEqualTo(NodeType.PARAGRAPH);
        }
    }
}
