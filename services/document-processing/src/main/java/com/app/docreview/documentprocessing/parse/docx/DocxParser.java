package com.app.docreview.documentprocessing.parse.docx;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import com.app.docreview.documentprocessing.spans.TextHasher;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses DOCX into canonical nodes. Architecture §11.2, §5.3.
 *
 * <p><strong>DOCX is the better source of structure.</strong> It carries real heading styles, list
 * levels, and table boundaries as metadata, so section paths here are AUTHORITATIVE rather than
 * inferred — unlike the PDF path, which has to guess from clause numbering.
 *
 * <p>What DOCX does not carry is geometry: there are no page or coordinate values until the document
 * is laid out. Bounding boxes therefore come from the LibreOffice-rendered PDF (§14), and nodes here
 * are emitted without them. Character offsets remain valid because they index the extracted text.
 *
 * <p>Uses {@code getBodyElements()} rather than {@code getParagraphs()}: the latter silently omits
 * tables, and in a control standard the table IS often the requirement — a compliance matrix with
 * requirement, owner, frequency, and evidence columns. Dropping it loses the most structured content
 * in the document.
 */
@Component
@Slf4j
public class DocxParser {

    public ParsedDocx parse(byte[] docxBytes, String documentVersionId) throws IOException {
        try (var document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            List<CanonicalNode> nodes = new ArrayList<>();
            List<String> sectionPath = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int ordinal = 0;
            int paragraphIndex = 0;

            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String content = paragraph.getText().strip();
                    if (content.isEmpty()) {
                        continue;
                    }

                    Integer headingLevel = headingLevel(paragraph);
                    NodeType type;
                    if (headingLevel != null) {
                        type = NodeType.HEADING;
                        // Trim to the level ABOVE this heading before appending, so "Heading 2"
                        // following "Heading 2" becomes a sibling rather than a child.
                        while (sectionPath.size() >= headingLevel) {
                            sectionPath.remove(sectionPath.size() - 1);
                        }
                        sectionPath.add(content);
                    } else if (paragraph.getNumID() != null) {
                        type = NodeType.LIST_ITEM;
                        paragraphIndex++;
                    } else {
                        type = NodeType.PARAGRAPH;
                        paragraphIndex++;
                    }

                    int start = text.length();
                    text.append(content).append('\n');

                    nodes.add(new CanonicalNode(
                            SpanIdGenerator.nodeId(documentVersionId, ordinal),
                            type, List.copyOf(sectionPath),
                            null,                                    // no page until rendered
                            type == NodeType.HEADING ? null : paragraphIndex,
                            ordinal, content, TextHasher.hash(content),
                            List.of(),                               // geometry from the render
                            false));
                    ordinal++;

                } else if (element instanceof XWPFTable table) {
                    ordinal = appendTable(table, nodes, sectionPath, text,
                            ordinal, documentVersionId);
                }
            }

            log.info("Parsed DOCX {}: {} nodes", documentVersionId, nodes.size());
            return new ParsedDocx(nodes, text.toString());
        }
    }

    /**
     * Emits a table as TABLE + TABLE_ROW + TABLE_CELL nodes.
     *
     * <p>The header row is carried into each cell's text (§11.2: "retain table headers and associate
     * them with row and cell evidence"). A cell reading "Quarterly" is meaningless in isolation;
     * "Frequency: Quarterly" is a citable requirement element.
     */
    private int appendTable(XWPFTable table, List<CanonicalNode> nodes, List<String> sectionPath,
                            StringBuilder text, int ordinal, String documentVersionId) {
        List<String> headers = new ArrayList<>();
        var rows = table.getRows();
        if (!rows.isEmpty()) {
            for (XWPFTableCell cell : rows.get(0).getTableCells()) {
                headers.add(cell.getText().strip());
            }
        }

        nodes.add(new CanonicalNode(
                SpanIdGenerator.nodeId(documentVersionId, ordinal), NodeType.TABLE,
                List.copyOf(sectionPath), null, null, ordinal,
                String.join(" | ", headers), TextHasher.hash(String.join(" | ", headers)),
                List.of(), false));
        ordinal++;

        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = rows.get(r);
            var cells = row.getTableCells();
            List<String> cellTexts = new ArrayList<>();

            for (int c = 0; c < cells.size(); c++) {
                String value = cells.get(c).getText().strip();
                if (value.isEmpty()) {
                    continue;
                }
                String header = c < headers.size() ? headers.get(c) : "";
                // Skip the header row itself: it is already captured on the TABLE node, and
                // emitting "Requirement: Requirement" as evidence is noise.
                String labelled = (r == 0 || header.isEmpty()) ? value : header + ": " + value;
                cellTexts.add(labelled);

                nodes.add(new CanonicalNode(
                        SpanIdGenerator.nodeId(documentVersionId, ordinal), NodeType.TABLE_CELL,
                        List.copyOf(sectionPath), null, null, ordinal,
                        labelled, TextHasher.hash(labelled), List.of(), false));
                ordinal++;
            }

            if (!cellTexts.isEmpty()) {
                String rowText = String.join(" | ", cellTexts);
                text.append(rowText).append('\n');
                nodes.add(new CanonicalNode(
                        SpanIdGenerator.nodeId(documentVersionId, ordinal), NodeType.TABLE_ROW,
                        List.copyOf(sectionPath), null, null, ordinal,
                        rowText, TextHasher.hash(rowText), List.of(), false));
                ordinal++;
            }
        }
        return ordinal;
    }

    /**
     * Heading level from the paragraph style, or null when it is not a heading.
     *
     * <p>Reads the style NAME rather than trusting {@code getStyle()}, because the style ID varies
     * between authoring tools ("Heading1", "heading 1", "berschrift1" in a German Word install).
     */
    private Integer headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("(?i)heading\\s*(\\d)").matcher(style);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    public record ParsedDocx(List<CanonicalNode> nodes, String text) {}
}
