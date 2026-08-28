package com.app.docreview.documentprocessing.parse.pdf;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts PDF text together with per-character positions. Architecture §11.2, §11.3.
 *
 * <p>PDFBox gives text OR positions easily, but the two must be kept in lockstep for bounding boxes
 * to mean anything. This subclass records a {@link TextPosition} for every character it emits, at the
 * same index, so a character range can later be converted to rectangles without re-parsing.
 *
 * <p><strong>The separator overrides are load-bearing.</strong> {@code writeString} is the only
 * method that receives {@link TextPosition} data, so a naive implementation captures glyphs but
 * silently drops every space and newline PDFBox inserts between them. The result is text with no
 * word or line boundaries at all — "Approvalevidenceisretained" — which then defeats every
 * downstream sentence split and structural heuristic. It looks like a parser producing nothing
 * useful rather than a missing override. Both separators are therefore recorded explicitly, with a
 * null position: they are synthetic characters with no glyph on the page.
 */
public class PositionalTextStripper extends PDFTextStripper {

    private final StringBuilder text = new StringBuilder();
    private final List<TextPosition> positions = new ArrayList<>();
    private final List<PageBounds> pageBounds = new ArrayList<>();

    public PositionalTextStripper() throws IOException {
        // Sorting by position matters for multi-column and tagged PDFs, where the content stream
        // order is not reading order.
        setSortByPosition(true);
    }

    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
        text.append(string);
        // string.length() and textPositions.size() can differ: one glyph may expand to several
        // characters (a ligature) or several glyphs map to one. Pad or truncate so index alignment
        // between text and positions is never broken — a shifted index silently moves every
        // highlight after it.
        for (int i = 0; i < string.length(); i++) {
            positions.add(i < textPositions.size() ? textPositions.get(i) : null);
        }
        super.writeString(string, textPositions);
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        String sep = getLineSeparator();
        text.append(sep);
        for (int i = 0; i < sep.length(); i++) {
            positions.add(null);
        }
    }

    @Override
    protected void writeWordSeparator() throws IOException {
        String sep = getWordSeparator();
        text.append(sep);
        for (int i = 0; i < sep.length(); i++) {
            positions.add(null);
        }
    }

    @Override
    protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
        // CROP box, not media box. PDFBox renders and PDF.js displays the crop box; when a PDF insets
        // its crop box from its media box — routine in print-prepared documents — the visible page is
        // smaller than the media box reports. Recording the media box while the browser lays out the
        // crop box offsets every highlight by the inset, uniformly, which reads as a calibration
        // error rather than the wrong-box bug it is.
        var box = page.getCropBox();
        // Normalised to [0, 360) in 90° steps: /Rotate is legally any multiple of 90, including
        // negative values, and a raw -90 would fail an equality check against 270 downstream.
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        pageBounds.add(new PageBounds(
                getCurrentPageNo(), box.getWidth(), box.getHeight(), text.length(), rotation));
        super.endPage(page);
    }

    public String extractedText() {
        return text.toString();
    }

    /** Positions parallel to {@link #extractedText()}; null for synthetic separators. */
    public List<TextPosition> characterPositions() {
        return positions;
    }

    public List<PageBounds> pages() {
        return pageBounds;
    }

    /**
     * @param width            CROP-box width in points — the dimension the viewer lays out
     * @param height           CROP-box height in points
     * @param endCharExclusive cumulative text length at the end of this page, so a character offset
     *                         can be attributed to a page without a second pass
     * @param rotation         the page's {@code /Rotate}, normalised to 0, 90, 180, or 270
     */
    public record PageBounds(int pageNumber, float width, float height, int endCharExclusive,
                             int rotation) {}
}
