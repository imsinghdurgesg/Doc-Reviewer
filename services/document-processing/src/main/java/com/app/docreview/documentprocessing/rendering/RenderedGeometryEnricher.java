package com.app.docreview.documentprocessing.rendering;

import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.evidence.HighlightQuality;
import com.app.docreview.documentprocessing.parse.pdf.PdfParser;
import com.app.docreview.documentprocessing.spans.PdfSpanGeometrySource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaches page geometry from a rendered PDF to spans parsed from a non-PDF source.
 * Architecture §11.3, §19.1.
 *
 * <p>A DOCX or text document has no coordinates until it is rendered, so its spans carry no bounding
 * boxes and the viewer can display the page but not highlight the evidence on it. This closes that
 * gap: the rendered PDF is parsed for glyph positions, each existing span's text is located in it,
 * and the resulting boxes are attached.
 *
 * <p><strong>Spans are ENRICHED, never regenerated.</strong> Span IDs are derived from
 * {@code (documentVersionId, ordinal, subIndex)}, so re-parsing the rendered PDF into fresh spans
 * would produce different ordinals — different IDs — and every requirement already indexed against
 * the original spans would cite identifiers that no longer exist (§11.3). Only the
 * {@code boundingBoxes} field changes; ID, text, hash, and section path are untouched.
 *
 * <p>Matching is by TEXT, not by position, because the two parses disagree structurally: the source
 * parser emits one span per logical paragraph, while the PDF parser sees whatever the renderer laid
 * out across lines and pages. Text is the only stable correspondence between them.
 *
 * <p>Unmatched spans keep their empty box list. That is the honest outcome — the finding stays valid
 * and citable (its text is verified against the stored span), it simply cannot be pointed at on the
 * page. Fabricating a box would draw a highlight over unrelated content.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RenderedGeometryEnricher {

    /**
     * Shortest span text worth locating.
     *
     * <p>A very short string ("Approval", "3.1") occurs many times in a policy document, and the
     * first hit is unlikely to be the right one. A highlight on the wrong occurrence is worse than no
     * highlight: it asserts the evidence is somewhere it is not.
     */
    private static final int MIN_MATCH_LENGTH = 12;

    private final PdfParser pdfParser;

    /**
     * Returns the spans with bounding boxes attached where they could be located.
     *
     * @param renderedPdf     the LibreOffice output for this document version
     * @param spans           spans parsed from the ORIGINAL source; returned with IDs preserved
     * @param renditionId     object key of the stored rendition these boxes are measured against
     * @param renditionSha256 hash of that rendition's exact bytes, so the viewer can detect that
     *                        stored rectangles belong to a different render and refuse to draw them
     */
    public List<EvidenceSpan> enrich(byte[] renderedPdf, List<EvidenceSpan> spans,
                                     String documentVersionId,
                                     String renditionId, String renditionSha256) {
        if (renderedPdf == null || renderedPdf.length == 0 || spans.isEmpty()) {
            return spans;
        }

        try {
            // Parsed under a throwaway version id: this parse exists only to obtain glyph positions,
            // and its own span/node IDs must never escape into storage.
            var parsed = pdfParser.parse(renderedPdf, documentVersionId + "-rendered");
            String haystack = parsed.text();
            var positions = parsed.positions();

            if (haystack == null || haystack.isBlank() || positions.isEmpty()) {
                log.warn("Rendered PDF for {} yielded no text positions; spans keep no geometry",
                        documentVersionId);
                return spans;
            }

            List<EvidenceSpan> enriched = new ArrayList<>(spans.size());
            int matched = 0;
            // Advances monotonically so repeated boilerplate maps to occurrences in reading order
            // rather than every span matching the first instance.
            int cursor = 0;

            // Geometry measured against the rendered PDF, addressed by character range. The span's
            // own text is located in the rendered text stream first, then that range is converted to
            // rectangles — so a sentence-span gets rectangles bounding the SENTENCE, not the
            // paragraph it came from.
            var geometry = new PdfSpanGeometrySource(
                    positions, parsed.pageBounds(), renditionId, renditionSha256);

            for (EvidenceSpan span : spans) {
                String needle = normalise(span.exactText());
                List<BoundingBox> boxes = List.of();
                // Set when the located text is only PART of the span, so the resulting boxes are
                // reported APPROXIMATE rather than claiming to delimit the whole record.
                boolean partialMatch = false;

                if (needle.length() >= MIN_MATCH_LENGTH) {
                    int at = indexOfNormalised(haystack, needle, cursor);
                    if (at < 0 && cursor > 0) {
                        // Fall back to a full scan: the renderer can reorder content (headers,
                        // footnotes), and one out-of-order span should not desynchronise the rest.
                        at = indexOfNormalised(haystack, needle, 0);
                    }
                    if (at < 0) {
                        // Structured sources render PRESENTATION text that the PDF does not contain:
                        // a CSV row is stored as "Control: 2.1 | Requirement: ..." while Calc lays out
                        // bare cells, and a JSON leaf as "approval / independentReviewer: false"
                        // while Writer prints the raw line. Retrying on the VALUES alone recovers the
                        // geometry; without it every structured document highlighted nothing.
                        //
                        // Tried in decreasing order of specificity: the joined values first, then
                        // the single LONGEST value. A spreadsheet is paginated by COLUMN, so one
                        // row's cells are not contiguous in the extracted text and the joined form
                        // can never match — but its longest cell is a distinctive phrase that does,
                        // and highlighting that cell points the reviewer at the right row.
                        for (String candidate : matchCandidates(span.exactText())) {
                            String value = normalise(candidate);
                            if (value.length() < MIN_MATCH_LENGTH) {
                                continue;
                            }
                            at = indexOfNormalised(haystack, value, cursor);
                            if (at < 0) {
                                at = indexOfNormalised(haystack, value, 0);
                            }
                            if (at >= 0) {
                                needle = value;
                                // Bounds a PART of the record, not its whole rendered text, so the
                                // highlight locates the row without delimiting it exactly.
                                partialMatch = true;
                                break;
                            }
                        }
                    }
                    if (at >= 0) {
                        // Via the geometry source, so a span whose text wraps across a page boundary
                        // gets rectangles on both pages instead of the tail being drawn on the head's.
                        boxes = geometry.rectanglesFor(at, at + needle.length());
                        if (!boxes.isEmpty()) {
                            matched++;
                            cursor = at + needle.length();
                        }
                    }
                }

                enriched.add(withBoxes(span, boxes, geometry, renditionId, renditionSha256,
                        partialMatch));
            }

            // Logged as a RATIO because that is the number worth watching: a format whose canonical
            // text does not survive rendering shows up here as a low match rate, long before anyone
            // reports "the highlight is wrong".
            log.info("Attached geometry to {} of {} span(s) for {} from rendition {}",
                    matched, spans.size(), documentVersionId, renditionSha256);
            return enriched;

        } catch (Exception e) {
            // Never fatal: geometry is a display convenience, and §11.3 evidence remains valid
            // without it.
            //
            // The STACK TRACE is logged, unlike elsewhere in this service. Failures here are bugs in
            // this class rather than bad input — an earlier autoboxing NPE was reported as a bare
            // "NullPointerException" and gave no way to locate it. The trace names our own frames;
            // the message is still omitted from anything user-facing (§10.5, §34).
            log.warn("Could not attach rendered geometry for {}: {}",
                    documentVersionId, e.getClass().getSimpleName(), e);
            return spans;
        }
    }

    /**
     * Removes the labels a structured parser adds for readability, leaving only document values.
     *
     * <p>{@code CsvParser} renders a row as {@code Control: 2.1 | Requirement: text} and
     * {@code JsonParser} a leaf as {@code approval / independentReviewer: false}. Those labels make a
     * citation interpretable, but they are NOT in the rendered PDF — so the enricher must be able to
     * match on the values alone.
     *
     * <p>Only used as a fallback, after the verbatim text has failed. A span whose exact text does
     * appear in the rendition should always match on that first: this is the looser attempt, and
     * running it first would risk locating a value in the wrong row.
     */
    private List<String> matchCandidates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        // A CSV row's cells, or a single JSON leaf.
        for (String part : text.split("\\|")) {
            String p = part.strip();
            // Drop everything up to the LAST ": " — a JSON trail is "a / b / key: value", and a
            // value may itself contain a colon.
            int colon = p.lastIndexOf(": ");
            if (colon >= 0) {
                p = p.substring(colon + 2).strip();
            }
            if (!p.isEmpty()) {
                values.add(p);
            }
        }
        if (values.isEmpty()) {
            return List.of();
        }

        List<String> candidates = new ArrayList<>();
        // All values joined: correct when the renderer kept the record on one line.
        candidates.add(String.join(" ", values));
        if (values.size() > 1) {
            // The longest single value: the most distinctive phrase in the record, and the only thing
            // that can match when the renderer split the record across columns or pages.
            candidates.add(values.stream()
                    .max(java.util.Comparator.comparingInt(String::length))
                    .orElse(values.get(0)));
        }
        return candidates;
    }

    /**
     * Collapses whitespace so a paragraph wrapped differently by the renderer still matches.
     *
     * <p>Field DELIMITERS are collapsed too. A spreadsheet source keeps its commas or tabs in the
     * canonical text ({@code 2.1,All production changes must be recorded}), while Calc renders the
     * same row as cells separated by layout whitespace — so the two never matched and every row of
     * every CSV came back with no geometry. Treating a delimiter as whitespace on both sides is what
     * makes the row locatable; it costs nothing for prose, where consecutive delimiters do not occur.
     */
    private String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[,;\\t]", " ").replaceAll("\\s+", " ").strip();
    }

    /**
     * Finds {@code needle} in {@code haystack} comparing whitespace-insensitively.
     *
     * <p>A direct {@code indexOf} fails almost always: the source parser joined a wrapped paragraph
     * with single spaces, while the rendered PDF's extracted text carries the renderer's own line
     * breaks. Comparing with whitespace collapsed on both sides is what makes the match possible.
     *
     * <p>Returns an offset into the ORIGINAL haystack, because that is what indexes the position
     * list.
     */
    private int indexOfNormalised(String haystack, String needle, int from) {
        if (needle.isEmpty()) {
            return -1;
        }
        int hLen = haystack.length();
        int nLen = needle.length();

        for (int start = Math.max(0, from); start < hLen; start++) {
            if (isSpace(haystack.charAt(start))) {
                continue;
            }
            int h = start;
            int n = 0;
            while (h < hLen && n < nLen) {
                char hc = haystack.charAt(h);
                char nc = needle.charAt(n);
                if (isSpace(hc) && isSpace(nc)) {
                    // Skip runs on both sides: one newline matches one space.
                    while (h < hLen && isSpace(haystack.charAt(h))) h++;
                    while (n < nLen && isSpace(needle.charAt(n))) n++;
                    continue;
                }
                if (isSpace(hc) && !isSpace(nc)) {
                    break;
                }
                if (hc != nc) {
                    break;
                }
                h++;
                n++;
            }
            if (n >= nLen) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Whitespace for matching purposes — including field delimiters.
     *
     * <p>Must agree with {@link #normalise}: the needle has its delimiters collapsed to spaces, so a
     * comma still present in the rendered haystack has to compare equal to one. Disagreement here
     * silently reduces the match rate rather than failing, which is the hardest kind of bug to see.
     */
    private boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ';';
    }

    /** Copies a span with new geometry, leaving identity and content untouched. */
    private EvidenceSpan withBoxes(EvidenceSpan span, List<BoundingBox> boxes,
                                   PdfSpanGeometrySource geometry,
                                   String renditionId, String renditionSha256,
                                   boolean partialMatch) {
        // Page comes from the geometry when we have it: a text source had none, and the viewer needs
        // a page to navigate to (§19.1).
        //
        // Assigned via an explicit Integer, NOT a ternary. `BoundingBox.page()` is a primitive `int`
        // while `EvidenceSpan.page()` is a nullable `Integer`, so a conditional expression mixing
        // them unboxes BOTH branches — and a text-source span whose page is null then throws
        // NullPointerException. That threw for every span of a Markdown standard, and the catch-all
        // in enrich() reported it as "could not attach geometry" with no hint that the bug was here.
        Integer page = span.page();
        if (!boxes.isEmpty()) {
            page = boxes.get(0).page();
        }

        // Located by matching this span's own text in the rendered stream, so the rectangles bound
        // exactly that text. Unmatched spans are UNRESOLVED rather than PAGE_ONLY: without geometry
        // there is no page either, because a converted source had none to begin with.
        HighlightQuality quality;
        if (boxes.isEmpty()) {
            quality = page != null ? HighlightQuality.PAGE_ONLY : HighlightQuality.UNRESOLVED;
        } else if (partialMatch) {
            // Located via one cell or value of a structured record: the highlight points at the right
            // row, but does not bound the whole span. Saying EXACT here would present a partial
            // rectangle as a precise boundary.
            quality = HighlightQuality.APPROXIMATE;
        } else {
            quality = HighlightQuality.EXACT;
        }

        return span.withGeometry(
                boxes,
                page,
                // Bound only when geometry exists: an id and hash on an empty box list would claim a
                // rendition relationship that carries no coordinates.
                boxes.isEmpty() ? null : renditionId,
                boxes.isEmpty() ? null : renditionSha256,
                quality,
                boxes.isEmpty() ? null : geometry.pageGeometry(boxes.get(0).page()));
    }
}
