package com.app.docreview.documentprocessing.parse.pdf;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.documentprocessing.canonical.RepeatedContentDetector;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import com.app.docreview.documentprocessing.spans.TextHasher;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a PDF into canonical nodes with positions. Architecture §11.2.
 *
 * <p>PDF carries no heading semantics — there is no "this is an H2" in the format. Structure must be
 * inferred, and this parser infers it from clause NUMBERING rather than font metrics. Numbering is
 * explicit in the document ("4.2 Approval") and survives the extraction; font size is a stylistic
 * signal that varies between publishers and is frequently identical for headings and emphasis.
 *
 * <p>A degradation is recorded when structure had to be inferred, so a reviewer knows the section
 * path is a best effort rather than authoritative metadata (§11.2).
 */
@Component
@Slf4j
public class PdfParser {

    /**
     * A numbered clause heading: {@code 4}, {@code 4.2}, {@code 4.2.1}, optionally followed by a
     * short title. Bounded to 80 characters because a "heading" longer than that is a paragraph
     * that happens to begin with a number — a list item such as "1. The approver must ensure ...".
     *
     * <p>Matching this pattern is NECESSARY but not SUFFICIENT: see {@link #isSectionTitle}. An 80
     * character bound alone still admits a complete requirement sentence.
     */
    private static final Pattern CLAUSE_HEADING = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\.?\\s+(\\S.{0,79})$");

    /**
     * Longest a numbered line's body may be and still be a section TITLE rather than a clause.
     *
     * <p>Mirrors {@code TextParser.MAX_HEADING_WORDS}, deliberately: the two parsers feed the same
     * downstream grouping, so a line classified as a heading from a text source and as a clause from
     * a PDF source would extract a different requirement set from the same document.
     */
    private static final int MAX_HEADING_WORDS = 9;

    /** A bulleted or lettered list item. */
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:[-•*•]|\\(?[a-z]\\)|\\(?[ivxlcdm]+\\))\\s+\\S.*$",
            Pattern.CASE_INSENSITIVE);

    public ParsedPdf parse(byte[] pdfBytes, String documentVersionId) throws IOException {
        try (var document = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
            var stripper = new PositionalTextStripper();
            stripper.getText(document);

            String text = stripper.extractedText();
            List<TextPosition> positions = stripper.characterPositions();
            var pageBounds = stripper.pages();

            if (text.isBlank()) {
                // Almost always a scanned image with no text layer. Named specifically because
                // "no text" and "parse failed" need different remedies from the user.
                log.warn("PDF {} produced no extractable text ({} pages)",
                        documentVersionId, document.getNumberOfPages());
                return new ParsedPdf(List.of(), text, positions, document.getNumberOfPages(), true,
                        pageBounds);
            }

            var linesByPage = linesPerPage(text, pageBounds);
            var repeated = RepeatedContentDetector.detect(linesByPage);

            var nodes = buildNodes(text, positions, pageBounds, repeated, documentVersionId);
            log.info("Parsed PDF {}: {} pages, {} nodes, {} repeated lines suppressed",
                    documentVersionId, document.getNumberOfPages(), nodes.size(), repeated.size());
            return new ParsedPdf(nodes, text, positions, document.getNumberOfPages(), false,
                    pageBounds);
        }
    }

    private List<CanonicalNode> buildNodes(String text, List<TextPosition> positions,
                                           List<PositionalTextStripper.PageBounds> pageBounds,
                                           java.util.Set<String> repeated,
                                           String documentVersionId) {
        List<CanonicalNode> nodes = new ArrayList<>();
        List<String> sectionPath = new ArrayList<>();
        int ordinal = 0;
        int paragraphOnPage = 0;
        int lastPage = -1;

        int cursor = 0;
        while (cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(cursor, lineEnd);
            String trimmed = line.strip();

            if (!trimmed.isEmpty()) {
                int page = pageFor(cursor, pageBounds);
                if (page != lastPage) {
                    paragraphOnPage = 0;
                    lastPage = page;
                }

                boolean isRepeated = repeated.contains(RepeatedContentDetector.normalize(trimmed));
                NodeType type = classify(trimmed, isRepeated, page, pageBounds);

                if (type == NodeType.HEADING) {
                    // Reached only when classify() already applied isSectionTitle(), so this re-match
                    // is for the depth GROUP, not a second classification decision. Keeping the two
                    // in sync matters: a line promoted here but not there would push a sentence onto
                    // the section path while the node itself stayed a paragraph.
                    var matcher = CLAUSE_HEADING.matcher(trimmed);
                    if (matcher.matches()) {
                        // Depth from the numbering: "4.2" is one level below "4". Trimming the path
                        // to depth-1 before appending keeps siblings as siblings rather than
                        // nesting each under the previous one.
                        int depth = matcher.group(1).split("\\.").length;
                        while (sectionPath.size() >= depth) {
                            sectionPath.remove(sectionPath.size() - 1);
                        }
                        sectionPath.add(trimmed);
                    }
                } else {
                    paragraphOnPage++;
                }

                nodes.add(new CanonicalNode(
                        SpanIdGenerator.nodeId(documentVersionId, ordinal),
                        type,
                        List.copyOf(sectionPath),
                        page,
                        type == NodeType.HEADING ? null : paragraphOnPage,
                        ordinal,
                        trimmed,
                        TextHasher.hash(trimmed),
                        BoundingBoxBuilder.forRange(positions, cursor, lineEnd, page),
                        isRepeated));
                ordinal++;
            }
            cursor = lineEnd + 1;
        }
        return nodes;
    }

    /**
     * Classifies a line.
     *
     * <p>Repeated content is typed by POSITION on the page: near the top it is a header, near the
     * bottom a footer. Both are marked so retrieval can exclude them (§11.2).
     */
    private NodeType classify(String line, boolean repeated, int page,
                              List<PositionalTextStripper.PageBounds> pageBounds) {
        if (repeated) {
            // Without geometry the distinction cannot be made; HEADER is the safer default since
            // both are excluded from retrieval either way.
            return NodeType.PAGE_HEADER;
        }
        var clause = CLAUSE_HEADING.matcher(line);
        // A numbered line is a heading only if its body reads like a TITLE. "3.1 Changes must be
        // approved before deployment." is the requirement itself and must stay a paragraph.
        if (clause.matches() && isSectionTitle(clause.group(2))) {
            return NodeType.HEADING;
        }
        if (LIST_ITEM.matcher(line).matches()) {
            return NodeType.LIST_ITEM;
        }
        return NodeType.PARAGRAPH;
    }

    /**
     * Distinguishes a section title from a numbered requirement sentence.
     *
     * <p><strong>Why this guard is load-bearing.</strong> Without it every numbered clause under 80
     * characters became a heading, with two compounding effects. The requirement text disappeared
     * into the section path instead of remaining content the extractor sees; and because heading
     * depth pops the path to {@code depth-1}, a depth-2 sentence REPLACED the depth-2 title above it,
     * so every following line nested under a sentence-shaped heading. Sections then collapsed into
     * one enormous group, {@code CandidateDetector} produced a single candidate for it, and one model
     * call extracted one requirement from the lot.
     *
     * <p>The loss is silent, which is what made it survive: a requirement buried in a section path is
     * never extracted, never evaluated, and never reported as a gap. Measured against the demo
     * standards, 4–6 real controls per document were being absorbed this way.
     *
     * <p>The heuristic — short, and no terminal punctuation — is copied from {@code TextParser},
     * where it was already correct. "3. Approval" is a title; "3.1 Changes must be approved before
     * deployment to production." is a clause.
     */
    private boolean isSectionTitle(String body) {
        String stripped = body.strip();
        // Terminal punctuation marks a sentence. A title does not end in one.
        if (stripped.endsWith(".") || stripped.endsWith(";") || stripped.endsWith(":")) {
            return false;
        }
        return stripped.split("\\s+").length <= MAX_HEADING_WORDS;
    }

    private int pageFor(int charOffset, List<PositionalTextStripper.PageBounds> pageBounds) {
        for (var bounds : pageBounds) {
            if (charOffset < bounds.endCharExclusive()) {
                return bounds.pageNumber();
            }
        }
        return pageBounds.isEmpty() ? 1 : pageBounds.get(pageBounds.size() - 1).pageNumber();
    }

    private List<List<String>> linesPerPage(String text,
                                            List<PositionalTextStripper.PageBounds> pageBounds) {
        List<List<String>> result = new ArrayList<>();
        int start = 0;
        for (var bounds : pageBounds) {
            int end = Math.min(bounds.endCharExclusive(), text.length());
            result.add(List.of(text.substring(start, Math.max(start, end)).split("\n")));
            start = end;
        }
        return result;
    }

    /**
     * @param positions retained so span construction can build bounding boxes without re-parsing
     * @param noTextLayer true for a scanned PDF; the caller reports this rather than failing
     */
    public record ParsedPdf(
            List<CanonicalNode> nodes,
            String text,
            List<TextPosition> positions,
            int pageCount,
            boolean noTextLayer,
            /**
             * Cumulative character offsets per page, so a caller can map an offset to a page without
             * guessing. Exposed because {@code TextPosition} carries no page number, and inferring
             * one from y-coordinate resets is unreliable — a heuristic version of this attributed
             * every span of a two-page document to page 2, including the title.
             */
            List<PositionalTextStripper.PageBounds> pageBounds
    ) {}
}
