package com.app.docreview.documentprocessing.parse.pdf;

import com.app.docreview.contracts.evidence.BoundingBox;
import org.apache.pdfbox.text.TextPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a character range into page rectangles. Architecture §11.3.
 *
 * <p><strong>The coordinate convention, stated once because getting it wrong is invisible.</strong>
 * PDFBox's {@link TextPosition#getYDirAdj()} is measured from the TOP of the page downward — not from
 * the PDF user-space origin at the bottom. The boxes produced here are therefore TOP-DOWN, and a
 * consumer must place them with {@code top = y} and never {@code top = pageHeight - y}.
 *
 * <p>That distinction is worth the paragraph: applying a bottom-up flip to top-down coordinates
 * puts a box at {@code pageHeight - y} instead of {@code y}. Near the vertical middle of a page the
 * two are close enough that it reads as a rounding error rather than an inverted axis, so it survives
 * casual review and only shows up as highlights that are consistently "a bit off" — which is exactly
 * how it was diagnosed the slow way in a previous implementation.
 *
 * <p>One rectangle per LINE, not per character. A per-character box list for a paragraph is hundreds
 * of rectangles the browser must composite; per-line is a handful and matches how a reader perceives
 * a highlight.
 */
public final class BoundingBoxBuilder {

    /** Vertical tolerance, in points, for treating two glyphs as being on the same line. */
    private static final float LINE_TOLERANCE = 2.0f;

    private BoundingBoxBuilder() {
    }

    /**
     * @param positions per-character positions, parallel to the extracted text; nulls (synthetic
     *                  separators) are skipped rather than treated as glyphs at the origin
     * @param page      the 1-based page these characters are on. Passed in because
     *                  {@link TextPosition} does NOT expose its page number — verified against
     *                  PDFBox 3.0.8, which offers getPageWidth/getPageHeight but no getPageNumber.
     *                  The caller already knows the page from the stripper's page bounds.
     */
    public static List<BoundingBox> forRange(List<TextPosition> positions, int start, int end, int page) {
        List<BoundingBox> boxes = new ArrayList<>();
        int from = Math.max(0, start);
        int to = Math.min(positions.size(), end);

        Float lineTop = null;
        float minX = 0, maxX = 0, minY = 0, maxY = 0;
        boolean open = false;

        for (int i = from; i < to; i++) {
            TextPosition p = positions.get(i);
            if (p == null) {
                continue;   // separator: no glyph, no geometry
            }

            float x = p.getXDirAdj();
            float y = p.getYDirAdj();
            float w = p.getWidthDirAdj();
            float h = p.getHeightDir();

            // A new line starts when the baseline moves beyond tolerance. The range passed here is
            // always within one page (the caller splits by page), so no page comparison is needed.
            boolean newLine = !open || Math.abs(y - lineTop) > LINE_TOLERANCE;
            if (newLine) {
                if (open) {
                    boxes.add(box(page, minX, minY, maxX, maxY));
                }
                lineTop = y;
                minX = x; maxX = x + w;
                // getYDirAdj() is the glyph BASELINE measured from the page top, so the box top is
                // one glyph-height above it. Using the baseline as the top shifts every box down by
                // a line height — subtly wrong in a way that looks almost right.
                minY = y - h; maxY = y;
                open = true;
            } else {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                minY = Math.min(minY, y - h);
                maxY = Math.max(maxY, y);
            }
        }
        if (open) {
            boxes.add(box(page, minX, minY, maxX, maxY));
        }
        return boxes;
    }

    private static BoundingBox box(int page, float minX, float minY, float maxX, float maxY) {
        return new BoundingBox(page, minX, minY, maxX - minX, maxY - minY);
    }
}
