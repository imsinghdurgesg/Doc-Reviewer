package com.app.docreview.documentprocessing.parse.csv;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import com.app.docreview.documentprocessing.spans.TextHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses delimited data into table nodes. Architecture §8.2, §11.2.
 *
 * <p><strong>A row is a record, not a paragraph.</strong> Routed through the prose parser, a CSV of
 * fifteen controls became one node: no blank lines means nothing terminates a paragraph. Each row is
 * therefore its own {@link NodeType#TABLE_ROW}, and each row's text is rendered with its COLUMN NAMES
 * so the citable text reads as a statement rather than as positional data.
 *
 * <pre>
 *   raw row     2.1,All production changes must be recorded,Change Manager
 *   node text   Control: 2.1 | Requirement: All production changes must be recorded | Owner: Change Manager
 * </pre>
 *
 * <p>That transformation is the point of this parser. A bare comma-joined row is nearly useless to
 * the extractor and to a reviewer reading a citation — "2.1" alone carries no obligation, while
 * "Requirement: All production changes must be recorded" does. Embedding the header also survives
 * column reordering, which a positional {@code row 8 column 3} anchor does not.
 *
 * <p><strong>Section path is the header row</strong>, so every row shares one section and
 * {@code CandidateDetector} groups the table as a unit rather than emitting a candidate per row.
 */
@Component
@Slf4j
public class CsvParser {

    /**
     * Longest field kept verbatim in a row's rendered text.
     *
     * <p>A free-text column can hold an entire policy paragraph; several of those joined would push
     * one row past what is useful as a single citation. Truncation is marked so a reviewer can tell
     * the evidence was abridged rather than assuming the cell was short.
     */
    private static final int MAX_FIELD_CHARS = 500;

    /** Above this, a header is treated as data: a real header row is short labels. */
    private static final int MAX_HEADER_FIELD_CHARS = 60;

    public ParsedCsv parse(byte[] content, String documentVersionId) {
        String raw = new String(content, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');

        char delimiter = detectDelimiter(raw);
        List<List<String>> rows = readRows(raw, delimiter);

        List<CanonicalNode> nodes = new ArrayList<>();
        StringBuilder documentText = new StringBuilder();
        int ordinal = 0;

        if (rows.isEmpty()) {
            log.warn("CSV {} contained no rows", documentVersionId);
            return new ParsedCsv(List.of(), "");
        }

        List<String> header = looksLikeHeader(rows.get(0)) ? rows.get(0) : null;
        int firstDataRow = header == null ? 0 : 1;

        // The header becomes the section, so rows group as one table for candidate detection.
        List<String> sectionPath = new ArrayList<>();
        if (header != null) {
            String title = String.join(", ", header);
            sectionPath.add(title);
            ordinal = addNode(nodes, documentText, documentVersionId, ordinal,
                    NodeType.TABLE, List.of(), title);
        }

        for (int r = firstDataRow; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(f -> f.strip().isEmpty())) {
                continue;
            }
            String text = renderRow(row, header);
            if (text.isBlank()) {
                continue;
            }
            ordinal = addNode(nodes, documentText, documentVersionId, ordinal,
                    NodeType.TABLE_ROW, sectionPath, text);
        }

        log.info("Parsed CSV {}: {} row(s) -> {} node(s), delimiter '{}', header {}",
                documentVersionId, rows.size(), nodes.size(),
                delimiter == '\t' ? "\\t" : delimiter, header != null ? "present" : "absent");
        return new ParsedCsv(nodes, documentText.toString());
    }

    /**
     * Picks the delimiter by consistency across lines, not by first occurrence.
     *
     * <p>A comma inside a quoted free-text cell is common, so counting raw occurrences on one line
     * favours the wrong character. The delimiter that yields the same field count on the most lines
     * is the real one.
     */
    private char detectDelimiter(String raw) {
        char best = ',';
        int bestScore = -1;
        for (char candidate : new char[] {',', '\t', ';', '|'}) {
            var counts = new java.util.HashMap<Integer, Integer>();
            int lines = 0;
            for (String line : raw.split("\n")) {
                if (line.strip().isEmpty()) {
                    continue;
                }
                lines++;
                if (lines > 50) {
                    break;   // a sample is enough, and bounds the cost on a large file
                }
                int fields = splitRow(line, candidate).size();
                if (fields > 1) {
                    counts.merge(fields, 1, Integer::sum);
                }
            }
            int score = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** Splits the whole document, honouring quoted cells that contain newlines. */
    private List<List<String>> readRows(String raw, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> row = new ArrayList<>();
        boolean inQuotes = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote is a literal quote, per RFC 4180.
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Single-line split, used only for delimiter scoring. */
    private List<String> splitRow(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }

    /**
     * Whether the first row is a header rather than data.
     *
     * <p>Header labels are short, non-empty, and contain no sentence punctuation. Getting this wrong
     * is cheap in one direction and costly in the other: treating data as a header loses one row from
     * the evidence, whereas treating a header as data yields a row whose "requirement" is the word
     * "Requirement".
     */
    private boolean looksLikeHeader(List<String> row) {
        if (row.size() < 2) {
            return false;
        }
        for (String field : row) {
            String f = field.strip();
            if (f.isEmpty() || f.length() > MAX_HEADER_FIELD_CHARS
                    || f.endsWith(".") || f.endsWith(";")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a row as {@code Column: value | Column: value}.
     *
     * <p>Without the header a citation reads "2.1 | All production changes must be recorded |
     * Change Manager" — the reviewer cannot tell which column carries the obligation, and neither
     * can the extractor. Empty cells are omitted rather than rendered as "Owner: ", which would
     * assert an absent value.
     */
    private String renderRow(List<String> row, List<String> header) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            String value = row.get(i).strip();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_FIELD_CHARS) {
                value = value.substring(0, MAX_FIELD_CHARS) + "…";
            }
            String label = header != null && i < header.size() ? header.get(i).strip() : null;
            parts.add(label == null || label.isEmpty() ? value : label + ": " + value);
        }
        return String.join(" | ", parts);
    }

    private int addNode(List<CanonicalNode> nodes, StringBuilder documentText,
                        String documentVersionId, int ordinal, NodeType type,
                        List<String> sectionPath, String content) {
        nodes.add(new CanonicalNode(
                SpanIdGenerator.nodeId(documentVersionId, ordinal),
                type,
                List.copyOf(sectionPath),
                // Unpaginated until rendered; null rather than 1 so the viewer can tell the
                // difference (§19.1). Geometry arrives from the rendered PDF.
                null,
                null,
                ordinal,
                content,
                TextHasher.hash(content),
                List.of(),
                false));
        // Must contain each node's content verbatim and in order: span offsets are resolved by
        // searching this text (DocumentProcessingJobRunner.charOffsets).
        documentText.append(content).append('\n');
        return ordinal + 1;
    }

    /** Parsed result. {@code text} is the reassembled document, for absolute span offsets (§11.3). */
    public record ParsedCsv(List<CanonicalNode> nodes, String text) {}
}
