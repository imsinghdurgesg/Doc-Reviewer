package com.app.docreview.contracts.evidence;

/**
 * The coordinate convention a {@link BoundingBox} list was produced in. Architecture §11.3.
 *
 * <p><strong>Recorded rather than assumed.</strong> Every rectangle this system has ever stored is
 * {@link #PDF_POINTS_TOP_LEFT}, and the convention is documented at both producer
 * ({@code BoundingBoxBuilder}) and consumer ({@code EvidenceHighlightLayer}). But a convention that
 * lives only in prose is one refactor away from silently changing: applying a bottom-up flip to
 * top-down coordinates puts a box at {@code pageHeight - y}, which near the middle of a page reads
 * as a rounding error rather than an inverted axis.
 *
 * <p>Stamping the space on the geometry turns that class of bug from invisible into assertable — the
 * viewer can refuse to draw boxes it does not know how to place, instead of drawing them wrongly.
 */
public enum CoordinateSpace {

    /**
     * PDF points, origin at the page's TOP-left, {@code y} growing DOWNWARD.
     *
     * <p>Derived from PDFBox's {@code getYDirAdj()}, which is already measured from the page top —
     * not from the PDF user-space origin at the bottom. A consumer places a box with {@code top = y}
     * and never {@code top = pageHeight - y}.
     *
     * <p>Points are scale-independent: the browser multiplies by the scale the page actually
     * rendered at, so the same stored rectangle is correct at every zoom level.
     */
    PDF_POINTS_TOP_LEFT
}
