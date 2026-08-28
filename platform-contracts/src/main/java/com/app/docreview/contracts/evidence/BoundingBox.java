package com.app.docreview.contracts.evidence;

/**
 * A rectangle on a rendered page, in PDF points. Architecture §11.3.
 *
 * <p>Produced by the parser from real glyph positions and stored — never computed in the browser
 * and never invented by a model. That is what makes a highlight verifiable: the coordinates trace
 * back to characters the parser actually saw.
 *
 * <p>The origin convention MUST be documented wherever these are consumed. Assuming PDF user space
 * (bottom-left) for top-down coordinates puts a box at {@code pageHeight - y} instead of {@code y},
 * which near the middle of a page looks like a rounding error rather than an inverted axis.
 */
public record BoundingBox(
        int page,
        double x,
        double y,
        double width,
        double height
) {}
