package com.app.docreview.documentprocessing.spans;

import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.PageGeometry;

import java.util.List;

/**
 * Supplies rectangles for an absolute character range, and the identity of the rendition they were
 * measured against. Architecture §11.3, §19.1.
 *
 * <p>This is what lets {@link SpanBuilder} give each sentence its OWN geometry instead of inheriting
 * the enclosing node's. The builder knows every span's character range; it does not know how to turn
 * a range into rectangles, because that depends on which PDF the geometry comes from — the source
 * document for a native PDF, or the rendered preview for a converted one.
 *
 * <p><strong>Every implementation must measure against exactly one PDF and report its hash.</strong>
 * Rectangles are only meaningful alongside the identity of the bytes they were measured against: a
 * re-render can change pagination while leaving text identical, so geometry without a binding cannot
 * be shown to apply to whatever the viewer loaded.
 */
public interface SpanGeometrySource {

    /**
     * Rectangles bounding exactly {@code [charStart, charEnd)} of the document text.
     *
     * @return one rectangle per rendered line, or empty when this range has no glyph positions —
     *         which the caller reads as "fall back to node geometry", never as an error
     */
    List<BoundingBox> rectanglesFor(int charStart, int charEnd);

    /** Identity of the rendition the rectangles were measured against; null when there is none. */
    String renditionId();

    /** {@code sha256:...} of that rendition's bytes; null when there is none. */
    String renditionSha256();

    /** Dimensions and rotation of one page, for staleness detection in the viewer. */
    PageGeometry pageGeometry(int pageNumber);

    /**
     * No geometry available.
     *
     * <p>Correct for a source with no glyph positions at all — a text or Markdown document before it
     * has been rendered. Spans built with this keep whatever the node carried, and the builder marks
     * them {@code PAGE_ONLY} or {@code UNRESOLVED} accordingly.
     */
    SpanGeometrySource NONE = new SpanGeometrySource() {
        @Override
        public List<BoundingBox> rectanglesFor(int charStart, int charEnd) {
            return List.of();
        }

        @Override
        public String renditionId() {
            return null;
        }

        @Override
        public String renditionSha256() {
            return null;
        }

        @Override
        public PageGeometry pageGeometry(int pageNumber) {
            return null;
        }
    };
}
