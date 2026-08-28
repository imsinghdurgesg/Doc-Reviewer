package com.app.docreview.documentprocessing.spans;

import com.app.docreview.documentprocessing.parse.pdf.PositionalTextStripper.PageBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Character-range to page-rectangle mapping. Architecture §11.3.
 *
 * <p>Uses null positions throughout: {@code BoundingBoxBuilder} skips nulls as synthetic separators,
 * so these tests exercise the PAGE-SPLITTING and metadata logic without constructing PDFBox
 * {@code TextPosition} objects, which cannot be built without a live document.
 */
class PdfSpanGeometrySourceTest {

    /** Two pages of 100 characters each. */
    private static final List<PageBounds> TWO_PAGES = List.of(
            new PageBounds(1, 595, 842, 100, 0),
            new PageBounds(2, 595, 842, 200, 90));

    private PdfSpanGeometrySource source(int positionCount) {
        // All nulls: enough entries to index, no glyphs to measure.
        var positions = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
        for (int i = 0; i < positionCount; i++) {
            positions.add(null);
        }
        return new PdfSpanGeometrySource(positions, TWO_PAGES, "rend-1", "sha256:abc");
    }

    @Test
    @DisplayName("page geometry carries the recorded size and rotation")
    void pageGeometryIsExposed() {
        var geometry = source(200);

        assertThat(geometry.pageGeometry(1))
                .satisfies(g -> {
                    assertThat(g.width()).isEqualTo(595);
                    assertThat(g.height()).isEqualTo(842);
                    assertThat(g.rotation()).isZero();
                });
        // Rotation is per PAGE, not per document: a scanned insert can be rotated on its own.
        assertThat(geometry.pageGeometry(2).rotation()).isEqualTo(90);
        assertThat(geometry.pageGeometry(99)).isNull();
    }

    @Test
    @DisplayName("rendition binding is reported so geometry can be verified against a load")
    void bindingIsReported() {
        assertThat(source(10).renditionId()).isEqualTo("rend-1");
        assertThat(source(10).renditionSha256()).isEqualTo("sha256:abc");
    }

    @Test
    @DisplayName("an empty or inverted range yields no rectangles")
    void degenerateRanges() {
        var geometry = source(200);

        assertThat(geometry.rectanglesFor(50, 50)).isEmpty();
        assertThat(geometry.rectanglesFor(80, 20)).isEmpty();
    }

    @Test
    @DisplayName("a range beyond the position list is clamped rather than throwing")
    void outOfBoundsIsClamped() {
        // The span text and the position list can disagree slightly; a partial highlight beats an
        // exception that costs the whole document its geometry.
        assertThat(source(50).rectanglesFor(40, 5_000)).isEmpty();
        assertThat(source(50).rectanglesFor(-10, 5)).isEmpty();
    }

    @Test
    @DisplayName("a range crossing a page boundary terminates instead of looping")
    void crossPageRangeTerminates() {
        // The regression guard that matters here: the page-splitting loop must always advance. A
        // range spanning both pages previously had no page-cut step at all — the tail was measured
        // against the head's page, drawing it at coordinates from a different layout.
        var geometry = source(200);

        // Completes (does not hang) and produces no boxes, since every position is a null separator.
        assertThat(geometry.rectanglesFor(90, 150)).isEmpty();
    }

    @Test
    @DisplayName("absent page bounds degrade to no geometry rather than an infinite loop")
    void missingPageBounds() {
        var positions = new java.util.ArrayList<org.apache.pdfbox.text.TextPosition>();
        for (int i = 0; i < 20; i++) {
            positions.add(null);
        }
        var geometry = new PdfSpanGeometrySource(positions, List.of(), null, null);

        assertThat(geometry.rectanglesFor(0, 20)).isEmpty();
        assertThat(geometry.pageGeometry(1)).isNull();
    }

    @Test
    @DisplayName("no positions means no geometry, not an error")
    void noPositions() {
        var geometry = new PdfSpanGeometrySource(null, null, null, null);

        assertThat(geometry.rectanglesFor(0, 10)).isEmpty();
        assertThat(geometry.renditionSha256()).isNull();
    }
}
