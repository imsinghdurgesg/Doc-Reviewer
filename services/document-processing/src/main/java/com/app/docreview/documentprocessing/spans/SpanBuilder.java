package com.app.docreview.documentprocessing.spans;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.CoordinateSpace;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.evidence.HighlightQuality;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds citable evidence spans from canonical nodes. Architecture §11.3.
 *
 * <p><strong>One span per SENTENCE, not per node.</strong> §11.3 requires "the smallest useful
 * passage rather than a complete page", and the reason is precision of citation: a finding about
 * independent approval should highlight the sentence that requires it, not the six-sentence paragraph
 * it sits in. A reviewer checking an over-broad citation has to re-read the paragraph to find the
 * relevant clause, which is the work the tool was supposed to do.
 *
 * <p>Table cells and headings are NOT split — they are already atomic, and sentence-splitting a
 * heading produces fragments that cite nothing.
 */
@Component
public class SpanBuilder {

    /**
     * Sentence boundary that tolerates policy-text abbreviations.
     *
     * <p>Splitting on bare {@code [.!?]} breaks "e.g.", "i.e.", "No. 5", "Fig. 2", and version
     * numbers — each of which fragments a requirement into halves that cite nothing useful. The
     * lookbehind list is the set that actually occurs in standards prose.
     */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?<![A-Z])(?<!\\b(?:e\\.g|i\\.e|etc|vs|No|Fig|Sec|Art|approx|ref|cf))"
            + "(?<!\\d)\\.(?=\\s+\\p{Lu})|[!?](?=\\s+\\p{Lu})");

    /** Below this length a fragment is not independently citable. */
    private static final int MIN_SPAN_CHARS = 12;

    /**
     * Ordering slots reserved per node for its sub-spans.
     *
     * <p>{@code ordinal * STRIDE + sub} keeps reading order stable, but only while {@code sub} stays
     * below the stride: a node with more sub-spans than this would order after the following node.
     * The sub-index is therefore clamped, which degrades ordering WITHIN one pathological node
     * instead of scrambling the document. Sentence splitting does not approach this; a minified JSON
     * line or a single-cell table of many sentences could.
     */
    private static final int SUB_SPAN_STRIDE = 1000;

    /**
     * @param charOffsetOf resolves a node's start offset in the document text, so span offsets are
     *                     absolute and usable for highlighting
     */
    public List<EvidenceSpan> build(String documentVersionId, List<CanonicalNode> nodes,
                                    java.util.function.ToIntFunction<CanonicalNode> charOffsetOf) {
        return build(documentVersionId, nodes, charOffsetOf, SpanGeometrySource.NONE);
    }

    /**
     * Builds spans with per-sentence geometry where the source can supply it.
     *
     * <p><strong>Why this overload exists.</strong> A node's rectangles bound the whole node. When a
     * paragraph is split into one span per sentence, giving every sentence the node's boxes highlights
     * the entire paragraph for a finding about one clause — the precise over-citation this class's
     * contract exists to avoid. The character range of each sentence is already known here
     * ({@code nodeStart + range[0]} to {@code nodeStart + range[1]}), so when the caller can turn a
     * character range into rectangles, each sentence gets its own.
     *
     * @param geometry resolves an absolute character range to rectangles; {@link
     *                 SpanGeometrySource#NONE} falls back to the node's own boxes, which is correct
     *                 for a source with no glyph positions at all
     */
    public List<EvidenceSpan> build(String documentVersionId, List<CanonicalNode> nodes,
                                    java.util.function.ToIntFunction<CanonicalNode> charOffsetOf,
                                    SpanGeometrySource geometry) {
        List<EvidenceSpan> spans = new ArrayList<>();

        for (CanonicalNode node : nodes) {
            // Repeated headers and footers get no spans: they are excluded from retrieval, so a
            // citable span pointing at one could only ever be a false citation.
            if (node.repeated()) {
                continue;
            }

            // A node with no text yields no citable span. Guarded rather than assumed: an atomic node
            // (heading, table cell) with null text would NPE on text().length() below, and the job
            // runner's catch-all would report it as a generic "failed during GENERATING_SPANS" with
            // the real cause only in the stack trace. The offset resolver already tolerates null text,
            // so this is a real state in the pipeline rather than a defensive hypothetical.
            if (node.text() == null || node.text().isBlank()) {
                continue;
            }

            int nodeStart = charOffsetOf.applyAsInt(node);
            List<int[]> ranges = atomic(node.nodeType())
                    ? List.of(new int[]{0, node.text().length()})
                    : sentenceRanges(node.text());

            int sub = 0;
            for (int[] range : ranges) {
                String text = node.text().substring(range[0], range[1]).strip();
                if (text.length() < MIN_SPAN_CHARS) {
                    continue;
                }

                int spanStart = nodeStart + range[0];
                int spanEnd = nodeStart + range[1];

                // Per-sentence rectangles when the source has glyph positions, falling back to the
                // node's boxes when it does not.
                //
                // The quality distinction is the point: boxes measured for THIS character range are
                // EXACT, whereas the node's boxes bound a region that merely contains the sentence.
                // Both are useful; conflating them is what lets an over-broad highlight look precise.
                var resolved = geometry.rectanglesFor(spanStart, spanEnd);
                List<BoundingBox> boxes;
                HighlightQuality quality;
                if (!resolved.isEmpty()) {
                    boxes = resolved;
                    quality = HighlightQuality.EXACT;
                } else if (node.boundingBoxes() != null && !node.boundingBoxes().isEmpty()) {
                    boxes = node.boundingBoxes();
                    quality = ranges.size() == 1
                            // The span IS the whole node, so the node's boxes bound it exactly.
                            ? HighlightQuality.EXACT
                            : HighlightQuality.APPROXIMATE;
                } else {
                    boxes = List.of();
                    // A known page with no rectangles is still navigable; no page at all is not.
                    quality = node.page() != null
                            ? HighlightQuality.PAGE_ONLY
                            : HighlightQuality.UNRESOLVED;
                }

                // Explicit Integer, NOT a ternary. `BoundingBox.page()` is a primitive `int` while
                // `CanonicalNode.page()` is a nullable `Integer`, so a conditional mixing them
                // unboxes BOTH branches — and a node with no page then throws NullPointerException.
                // The same trap is documented in RenderedGeometryEnricher.withBoxes; it is this
                // easy to reintroduce.
                Integer page = node.page();
                if (!boxes.isEmpty()) {
                    page = boxes.get(0).page();
                }

                spans.add(new EvidenceSpan(
                        SpanIdGenerator.spanId(documentVersionId, node.ordinal(), sub),
                        documentVersionId,
                        page,
                        node.sectionPath(),
                        node.paragraph(),
                        spanStart,
                        spanEnd,
                        text,
                        TextHasher.hash(text),
                        boxes,
                        node.nodeId(),
                        // Ordering keeps reading order stable across sub-spans of one node.
                        // SUB_SPAN_STRIDE bounds how many sub-spans a node may have before its
                        // ordering would collide with the next node's.
                        (long) node.ordinal() * SUB_SPAN_STRIDE + Math.min(sub, SUB_SPAN_STRIDE - 1),
                        geometry.renditionId(),
                        geometry.renditionSha256(),
                        quality,
                        CoordinateSpace.PDF_POINTS_TOP_LEFT,
                        boxes.isEmpty() ? null : geometry.pageGeometry(boxes.get(0).page())));
                sub++;
            }
        }
        return spans;
    }

    /** Node types that are already the smallest useful passage. */
    private boolean atomic(NodeType type) {
        return type == NodeType.HEADING
                || type == NodeType.TABLE
                || type == NodeType.TABLE_ROW
                || type == NodeType.TABLE_CELL
                || type == NodeType.CAPTION
                || type == NodeType.CODE_BLOCK;
    }

    private List<int[]> sentenceRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        var matcher = SENTENCE_END.matcher(text);
        int start = 0;
        while (matcher.find()) {
            // +1 keeps the terminating punctuation with its sentence: a quote ending mid-punctuation
            // reads as truncated evidence.
            int end = Math.min(matcher.end() + 1, text.length());
            ranges.add(new int[]{start, end});
            start = end;
        }
        if (start < text.length()) {
            ranges.add(new int[]{start, text.length()});
        }
        return ranges.isEmpty() ? List.of(new int[]{0, text.length()}) : ranges;
    }
}
