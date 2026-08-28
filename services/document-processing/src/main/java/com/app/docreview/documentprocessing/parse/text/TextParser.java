package com.app.docreview.documentprocessing.parse.text;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import com.app.docreview.documentprocessing.spans.TextHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses plain text and Markdown into canonical nodes. Architecture §8.2, §11.2.
 *
 * <p>Produces the same {@link CanonicalNode} shape as the PDF and DOCX parsers, so everything
 * downstream — span building, chunking, retrieval, evidence hydration — is unchanged. A format that
 * produced a different node shape would need its own path through the whole pipeline.
 *
 * <p><strong>Section paths are the point.</strong> §11.2 requires heading structure to be preserved,
 * and §12.2 uses it to find requirement candidates: a numbered clause under "5. Access Review" is a
 * different obligation from the same words under "7. Guidance". Flattening a document to a list of
 * paragraphs loses exactly the context that distinguishes a mandatory control from a recommendation.
 *
 * <p>Two heading conventions are recognised, because policy documents in the wild use both:
 *
 * <ul>
 *   <li><strong>Markdown</strong> — {@code #}, {@code ##}, … where depth is the marker count.</li>
 *   <li><strong>Numbered</strong> — {@code 3.1 Account Provisioning}, where depth is the number of
 *       dot-separated segments. This is how the standards this system reviews are actually written,
 *       and without it every clause would sit at the document root.</li>
 * </ul>
 *
 * <p>No bounding boxes are emitted. A text file has no geometry, so §19.1's highlight overlay has
 * nothing to draw — the evidence text and section path still resolve, which is what makes a finding
 * citable. Returning invented coordinates would put highlights at arbitrary positions on the page.
 */
@Component
@Slf4j
public class TextParser {

    /** ATX-style Markdown heading: leading #s, then the text. */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /**
     * A numbered clause or section: {@code 3}, {@code 3.1}, {@code 3.1.2}, optionally followed by a
     * title. Requires a space after the number so a decimal inside a sentence is not mistaken for a
     * clause number.
     */
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+(\\S.*)$");

    /** Markdown list item: -, *, + or `1.` at the start of a line. */
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(\\S.*)$");

    /**
     * A numbered heading whose body is a full sentence is treated as a CLAUSE, not a section title.
     *
     * <p>"3.1 Access must be approved by the data owner" is the requirement itself; "3. Account
     * Provisioning" is a section containing requirements. Getting this wrong either buries every
     * obligation in a section path or produces a document with no structure at all. The heuristic:
     * a title is short and has no terminal punctuation.
     */
    private static final int MAX_HEADING_WORDS = 9;

    /** Below this many non-blank lines, record detection is not attempted. */
    private static final int MIN_RECORD_LINES = 3;

    /** Share of non-blank lines that must look like records before the file is treated as one. */
    private static final int RECORD_LINE_PERCENT = 70;

    /** Longest a CSV field may be and still count as compact evidence of a record. */
    private static final int MAX_COMPACT_FIELD_CHARS = 24;

    /** Most words a compact field may hold: "Change Manager" yes, a clause no. */
    private static final int MAX_COMPACT_FIELD_WORDS = 3;

    /** Parsed result. {@code text} is the reassembled document, for absolute span offsets (§11.3). */
    public record ParsedText(List<CanonicalNode> nodes, String text) {}

    public ParsedText parse(byte[] content, String documentVersionId) {
        String raw = new String(content, StandardCharsets.UTF_8);
        // Normalise line endings so a CRLF file does not leave \r inside every node's text and break
        // the text hashes that detect evidence mutation (§11.3).
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        List<CanonicalNode> nodes = new ArrayList<>();
        List<String> sectionPath = new ArrayList<>();
        StringBuilder documentText = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        int ordinal = 0;

        // Record-oriented content is line-delimited, not blank-line delimited. Accumulating it as
        // prose merges the WHOLE FILE into one node: a CSV of fifteen controls became a single
        // unciteable span, so retrieval could only ever return "the entire document" and the
        // extractor saw one blob instead of fifteen requirements.
        boolean lineOriented = isRecordOriented(lines);

        for (String line : lines) {
            String trimmed = line.strip();

            // A blank line ends the current paragraph. This is the only paragraph delimiter in plain
            // text, so it has to be honoured before any other rule.
            if (trimmed.isEmpty()) {
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                continue;
            }

            // One node per line, flushed immediately. A CSV row and a JSON member are each a complete
            // record: joining them would be as wrong as splitting a wrapped sentence.
            if (lineOriented) {
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                paragraph.append(trimmed);
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                continue;
            }

            var markdown = MARKDOWN_HEADING.matcher(line);
            if (markdown.matches()) {
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                int depth = markdown.group(1).length();
                String title = markdown.group(2).strip();
                ordinal = addHeading(nodes, sectionPath, documentText, documentVersionId,
                        ordinal, title, depth);
                continue;
            }

            var numbered = NUMBERED_HEADING.matcher(trimmed);
            if (numbered.matches() && isSectionTitle(numbered.group(2))) {
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                // Depth from the numbering itself: "5.1" nests under "5".
                int depth = numbered.group(1).split("\\.").length;
                // The number is kept in the heading text so a reviewer can navigate by "5.1", which
                // is how people actually cite a standard (§12.7).
                ordinal = addHeading(nodes, sectionPath, documentText, documentVersionId,
                        ordinal, trimmed, depth);
                continue;
            }

            var listItem = LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                        documentVersionId, ordinal);
                ordinal = addNode(nodes, documentText, documentVersionId, ordinal,
                        NodeType.LIST_ITEM, sectionPath, listItem.group(1).strip());
                continue;
            }

            // Ordinary line: accumulate. Wrapped prose in a text file is one logical paragraph split
            // across several lines, and treating each line as a node would fragment every
            // requirement into unciteable pieces.
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }

        ordinal = flushParagraph(paragraph, nodes, sectionPath, documentText,
                documentVersionId, ordinal);

        log.debug("Parsed text document {} into {} node(s)", documentVersionId, nodes.size());
        return new ParsedText(nodes, documentText.toString());
    }

    /**
     * Whether the content is record-oriented (CSV/TSV rows, or JSON) rather than prose.
     *
     * <p>Detected from CONTENT, not the filename: this parser is handed bytes and a version id, and
     * the same shape arrives as {@code .csv}, {@code .txt}, or an extension the dispatcher did not
     * recognise. Content is also the honest signal — a {@code .csv} of prose paragraphs should be
     * read as prose.
     *
     * <p>Deliberately conservative. Misreading prose as records fragments every wrapped sentence
     * into unciteable line pieces, which is the failure the paragraph accumulator exists to prevent.
     * So a document only qualifies when it has almost no blank lines AND a strong majority of its
     * lines carry the same delimiter structure.
     */
    private boolean isRecordOriented(String[] lines) {
        int nonBlank = 0;
        int blank = 0;
        int delimited = 0;
        int jsonish = 0;

        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) {
                blank++;
                continue;
            }
            nonBlank++;
            // A delimited RECORD, not merely a line containing commas. Prose uses commas freely
            // ("recorded, approved, and tested"), so a comma count alone misfires — and misreading
            // prose as records fragments every wrapped requirement into unciteable pieces.
            //
            // The discriminator is FIELD SHAPE: a CSV field is short and unpunctuated, whereas prose
            // between commas is a clause with spaces and a terminating period. Tabs need no such
            // test; they effectively never appear in policy prose.
            if (countOf(t, '\t') >= 1 || looksLikeDelimitedRow(t)) {
                delimited++;
            }
            if (t.startsWith("{") || t.startsWith("}") || t.startsWith("[") || t.startsWith("]")
                    || t.matches("^\"[^\"]+\"\\s*:.*")) {
                jsonish++;
            }
        }

        if (nonBlank < MIN_RECORD_LINES) {
            // Too small to tell, and the cost of being wrong outweighs the benefit.
            return false;
        }
        // Prose is paragraph-separated; records generally are not.
        if (blank > nonBlank / 2) {
            return false;
        }
        return delimited * 100 / nonBlank >= RECORD_LINE_PERCENT
                || jsonish * 100 / nonBlank >= RECORD_LINE_PERCENT;
    }

    /**
     * Whether a line is a comma-DELIMITED row rather than prose that happens to contain commas.
     *
     * <p>Tested on field shape: a line ending in a sentence terminator is prose, and a row whose
     * fields are mostly long multi-word clauses is prose too. A real CSV row has at least one short,
     * compact field — the key, code, or control number that makes it a record.
     */
    private boolean looksLikeDelimitedRow(String line) {
        if (countOf(line, ',') < 2) {
            return false;
        }
        // A record does not end like a sentence.
        if (line.endsWith(".") || line.endsWith(";")) {
            return false;
        }
        String[] fields = line.split(",", -1);
        int compact = 0;
        for (String field : fields) {
            String f = field.strip();
            // "2.1", "Change Manager", "Security Lead" — short and not a clause.
            if (!f.isEmpty() && f.length() <= MAX_COMPACT_FIELD_CHARS
                    && f.split("\\s+").length <= MAX_COMPACT_FIELD_WORDS) {
                compact++;
            }
        }
        // At least two compact fields: one alone occurs in prose ("Yes, the approver must ...").
        return compact >= 2;
    }

    private int countOf(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * True when a numbered line looks like a section TITLE rather than a requirement.
     *
     * <p>Short, and without the terminal punctuation of a sentence. "Access Review and Revocation"
     * is a title; "Access must be approved by the data owner before the account is created." is a
     * clause that happens to start with a number.
     */
    private boolean isSectionTitle(String body) {
        String stripped = body.strip();
        if (stripped.endsWith(".") || stripped.endsWith(";") || stripped.endsWith(":")) {
            return false;
        }
        return stripped.split("\\s+").length <= MAX_HEADING_WORDS;
    }

    private int addHeading(List<CanonicalNode> nodes, List<String> sectionPath,
                           StringBuilder documentText, String documentVersionId,
                           int ordinal, String title, int depth) {
        // Pop deeper-or-equal levels before pushing, so "5.1" replaces "4.2" rather than nesting
        // under it. Without this the section path grows monotonically and every later clause claims
        // to live inside every earlier section.
        while (sectionPath.size() >= depth && !sectionPath.isEmpty()) {
            sectionPath.remove(sectionPath.size() - 1);
        }
        sectionPath.add(title);
        return addNode(nodes, documentText, documentVersionId, ordinal,
                NodeType.HEADING, sectionPath, title);
    }

    private int flushParagraph(StringBuilder paragraph, List<CanonicalNode> nodes,
                               List<String> sectionPath, StringBuilder documentText,
                               String documentVersionId, int ordinal) {
        if (paragraph.length() == 0) {
            return ordinal;
        }
        String content = paragraph.toString().strip();
        paragraph.setLength(0);
        if (content.isEmpty()) {
            return ordinal;
        }
        return addNode(nodes, documentText, documentVersionId, ordinal,
                NodeType.PARAGRAPH, sectionPath, content);
    }

    private int addNode(List<CanonicalNode> nodes, StringBuilder documentText,
                        String documentVersionId, int ordinal, NodeType type,
                        List<String> sectionPath, String content) {
        nodes.add(new CanonicalNode(
                SpanIdGenerator.nodeId(documentVersionId, ordinal),
                type,
                List.copyOf(sectionPath),
                // No page: a text file has no pagination. Null rather than 1, so the viewer can tell
                // "unpaginated" from "page one" (§19.1).
                null,
                null,
                ordinal,
                content,
                TextHasher.hash(content),
                // No geometry in a text file; see the class comment.
                List.of(),
                false));

        // The reassembled text must contain each node's content verbatim and in order, because span
        // offsets are resolved by searching it (see DocumentProcessingJobRunner.charOffsets).
        documentText.append(content).append('\n');
        return ordinal + 1;
    }
}
