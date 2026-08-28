package com.app.docreview.contracts.evidence;

/**
 * The page a rectangle was measured against. Architecture §11.3, §19.1.
 *
 * <p><strong>Why the viewer cannot just ask PDF.js.</strong> It can, and it does — but only for the
 * page it is currently displaying, and only for the rendition it happens to have loaded. Storing the
 * dimensions the geometry was produced against makes a mismatch detectable: if the stored size and
 * the viewport size disagree, the rectangles were measured against a different layout and must not
 * be drawn as exact.
 *
 * <p><strong>{@code width}/{@code height} are CROP-box dimensions, not media box.</strong> PDFBox
 * renders and PDF.js displays the crop box; a PDF whose crop box is inset from its media box (common
 * in print-prepared documents) has a smaller visible page than its media box reports. Measuring
 * against the media box while the browser lays out the crop box offsets every highlight by the
 * inset — uniformly, so it looks like a systematic calibration error rather than a box-selection bug.
 *
 * @param pageNumber 1-based, matching {@link BoundingBox#page()}
 * @param width      crop-box width in PDF points
 * @param height     crop-box height in PDF points
 * @param rotation   the page's {@code /Rotate} value, normalised to 0, 90, 180, or 270. Recorded
 *                   because PDF.js applies rotation in its viewport transform while PDFBox's
 *                   direction-adjusted coordinates have already absorbed it, so a rotated page needs
 *                   the viewer to know not to rotate twice.
 */
public record PageGeometry(
        int pageNumber,
        double width,
        double height,
        int rotation
) {}
