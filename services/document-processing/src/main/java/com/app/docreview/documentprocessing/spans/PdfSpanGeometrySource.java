package com.app.docreview.documentprocessing.spans;

import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.PageGeometry;
import com.app.docreview.documentprocessing.parse.pdf.BoundingBoxBuilder;
import com.app.docreview.documentprocessing.parse.pdf.PositionalTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry read from the glyph positions of one PDF. Architecture §11.3, §19.1.
 *
 * <p>Backs the exact-geometry guarantee for native PDFs: the rectangles for a span come from the
 * same parse that produced the span, so no text search, normalisation, or fuzzy alignment is
 * involved — the coordinates trace back to characters the parser actually saw at those offsets.
 *
 * <p><strong>A range may cross a page boundary.</strong> A sentence split across pages needs
 * rectangles on both, and {@code BoundingBoxBuilder} works within a single page because
 * {@code TextPosition} carries no page number. So the range is split at page boundaries first and
 * each part measured against its own page. Measuring the whole range as one page's worth would
 * attribute the tail to the head's page and draw it at coordinates from a different layout.
 */
public final class PdfSpanGeometrySource implements SpanGeometrySource {

    private final List<TextPosition> positions;
    private final List<PositionalTextStripper.PageBounds> pageBounds;
    private final String renditionId;
    private final String renditionSha256;

    public PdfSpanGeometrySource(List<TextPosition> positions,
                                 List<PositionalTextStripper.PageBounds> pageBounds,
                                 String renditionId,
                                 String renditionSha256) {
        this.positions = positions == null ? List.of() : positions;
        this.pageBounds = pageBounds == null ? List.of() : pageBounds;
        this.renditionId = renditionId;
        this.renditionSha256 = renditionSha256;
    }

    @Override
    public List<BoundingBox> rectanglesFor(int charStart, int charEnd) {
        if (positions.isEmpty() || charEnd <= charStart) {
            return List.of();
        }
        // Clamp rather than reject: an offset past the end means the caller's text and this position
        // list disagree slightly, and a partial highlight beats none.
        int from = Math.max(0, charStart);
        int to = Math.min(positions.size(), charEnd);
        if (to <= from) {
            return List.of();
        }

        List<BoundingBox> boxes = new ArrayList<>();
        int cursor = from;
        while (cursor < to) {
            int page = pageAt(cursor);
            int pageEnd = pageEndExclusive(page);
            // The part of the range that lies on this page.
            int segmentEnd = Math.min(to, pageEnd);
            if (segmentEnd <= cursor) {
                // No forward progress would loop for ever. Can happen if page bounds are absent or
                // non-monotonic; bail with what has been collected.
                break;
            }
            boxes.addAll(BoundingBoxBuilder.forRange(positions, cursor, segmentEnd, page));
            cursor = segmentEnd;
        }
        return boxes;
    }

    @Override
    public String renditionId() {
        return renditionId;
    }

    @Override
    public String renditionSha256() {
        return renditionSha256;
    }

    @Override
    public PageGeometry pageGeometry(int pageNumber) {
        for (var bounds : pageBounds) {
            if (bounds.pageNumber() == pageNumber) {
                return new PageGeometry(
                        bounds.pageNumber(), bounds.width(), bounds.height(), bounds.rotation());
            }
        }
        return null;
    }

    /** 1-based page for a character offset, using the parser's own boundaries. */
    private int pageAt(int offset) {
        for (var bounds : pageBounds) {
            if (offset < bounds.endCharExclusive()) {
                return bounds.pageNumber();
            }
        }
        return pageBounds.isEmpty() ? 1 : pageBounds.get(pageBounds.size() - 1).pageNumber();
    }

    /** Exclusive character end of a page, so a range can be cut at the boundary. */
    private int pageEndExclusive(int pageNumber) {
        for (var bounds : pageBounds) {
            if (bounds.pageNumber() == pageNumber) {
                return bounds.endCharExclusive();
            }
        }
        return Integer.MAX_VALUE;
    }
}
