package com.app.docreview.contracts.evidence;

import java.util.List;

/**
 * An immutable, addressable passage of a document version. Architecture §11.3.
 *
 * <p><strong>This record is the reason the model never quotes text.</strong> Models return
 * {@code spanId} values (§16.4); the backend validates ownership and then hydrates exact text and
 * location from the stored span (§16.5). Two consequences follow:
 *
 * <ul>
 *   <li>Fabrication is structurally impossible. An unknown or foreign span ID is rejected, so a
 *       model cannot invent a plausible quotation or a page number.</li>
 *   <li>There is no re-location step. Nothing has to search the document for a returned string,
 *       so no fuzzy matching, no normalization, and no disagreement between the parser's character
 *       offsets and the viewer's.</li>
 * </ul>
 *
 * <p>{@code textHash} lets a stored span be checked against the document it claims to come from, so
 * a re-parse that silently changes offsets is detectable rather than corrupting old comparisons.
 *
 * <h2>Geometry is bound to one rendition</h2>
 *
 * <p>Rectangles are measured against a SPECIFIC rendered PDF, so they are only meaningful alongside
 * the identity of that PDF. {@code renditionId} and {@code renditionSha256} carry it. A viewer that
 * has loaded a different rendition — a re-render with a newer LibreOffice, different fonts, or
 * different page setup — must treat the stored rectangles as stale rather than drawing them, because
 * re-rendering can change pagination and line wrapping while leaving the text identical.
 *
 * <p>This is why the geometry fields stay on the span rather than moving to a separate mapping
 * collection: the binding is what makes them safe, not the location of the record. Stored rectangles
 * remain AUTHORITATIVE for the exact immutable rendition they were extracted from.
 *
 * @param spanId            stable identifier, unique within the document version
 * @param documentVersionId the immutable version this span belongs to; ownership is validated
 *                          against it before any evidence is displayed
 * @param exactText         the passage as parsed, byte-for-byte
 * @param textHash          {@code sha256:...} of {@code exactText}
 * @param sectionPath       heading trail, outermost first
 * @param boundingBoxes     zero or more rectangles; empty when the source has no rendered geometry
 *                          (a text-only standard), which is legitimate and must not block display
 * @param ordering          stable sort value for reading order
 * @param renditionId       the rendition {@code boundingBoxes} were measured against; null for a
 *                          span that has no geometry
 * @param renditionSha256   {@code sha256:...} of that rendition's bytes. The viewer compares this
 *                          against the PDF it actually loaded and suppresses geometry on a mismatch
 * @param highlightQuality  how precisely the rectangles locate this span's text; null is read as
 *                          {@link HighlightQuality#UNRESOLVED} for spans written before this field
 *                          existed
 * @param coordinateSpace   the convention {@code boundingBoxes} are expressed in; null is read as
 *                          {@link CoordinateSpace#PDF_POINTS_TOP_LEFT}, which is what every
 *                          previously stored rectangle used
 * @param pageGeometry      the page these rectangles were measured against, for staleness detection
 */
public record EvidenceSpan(
        String spanId,
        String documentVersionId,
        Integer page,
        List<String> sectionPath,
        Integer paragraph,
        Integer charStart,
        Integer charEnd,
        String exactText,
        String textHash,
        List<BoundingBox> boundingBoxes,
        String parentNodeId,
        long ordering,
        String renditionId,
        String renditionSha256,
        HighlightQuality highlightQuality,
        CoordinateSpace coordinateSpace,
        PageGeometry pageGeometry
) {

    /**
     * Legacy shape, without rendition binding or geometry metadata.
     *
     * <p>Retained so the many construction sites that predate those fields keep compiling and keep
     * meaning the same thing. A span built this way declares its geometry {@code UNRESOLVED} rather
     * than defaulting to {@code EXACT}: an unbound rectangle cannot be shown to belong to the
     * rendition the viewer loaded, and claiming precision that was never established is the specific
     * failure the quality field exists to prevent.
     */
    public EvidenceSpan(
            String spanId,
            String documentVersionId,
            Integer page,
            List<String> sectionPath,
            Integer paragraph,
            Integer charStart,
            Integer charEnd,
            String exactText,
            String textHash,
            List<BoundingBox> boundingBoxes,
            String parentNodeId,
            long ordering) {
        this(spanId, documentVersionId, page, sectionPath, paragraph, charStart, charEnd,
                exactText, textHash, boundingBoxes, parentNodeId, ordering,
                null, null,
                boundingBoxes == null || boundingBoxes.isEmpty()
                        ? HighlightQuality.UNRESOLVED
                        : HighlightQuality.APPROXIMATE,
                CoordinateSpace.PDF_POINTS_TOP_LEFT,
                null);
    }

    /**
     * Normalises the nullable additions so readers never branch on null.
     *
     * <p>Spans persisted before these fields existed deserialise with them absent. Defaulting here —
     * once — keeps every consumer from repeating the same null check, and keeps a missing value from
     * being read as a positive claim about precision.
     */
    public EvidenceSpan {
        if (highlightQuality == null) {
            highlightQuality = boundingBoxes == null || boundingBoxes.isEmpty()
                    ? HighlightQuality.UNRESOLVED
                    : HighlightQuality.APPROXIMATE;
        }
        if (coordinateSpace == null) {
            coordinateSpace = CoordinateSpace.PDF_POINTS_TOP_LEFT;
        }
    }

    /**
     * Whether stored rectangles may be drawn against the rendition the viewer has loaded.
     *
     * <p>The comparison is on the rendition HASH, not the id: an id can be reused by a re-render,
     * whereas the hash changes whenever the bytes do. A span with no binding (legacy, or no
     * geometry) returns false — unbound geometry is exactly what must not be trusted.
     *
     * @param loadedRenditionSha256 hash of the PDF the viewer actually fetched
     */
    public boolean geometryAppliesTo(String loadedRenditionSha256) {
        return renditionSha256 != null
                && !renditionSha256.isBlank()
                && renditionSha256.equals(loadedRenditionSha256)
                && boundingBoxes != null
                && !boundingBoxes.isEmpty();
    }

    /** Copy with geometry replaced and bound to the rendition it was measured against. */
    public EvidenceSpan withGeometry(List<BoundingBox> boxes,
                                     Integer resolvedPage,
                                     String renditionId,
                                     String renditionSha256,
                                     HighlightQuality quality,
                                     PageGeometry geometry) {
        return new EvidenceSpan(
                spanId, documentVersionId, resolvedPage, sectionPath, paragraph,
                charStart, charEnd, exactText, textHash, boxes, parentNodeId, ordering,
                renditionId, renditionSha256, quality, CoordinateSpace.PDF_POINTS_TOP_LEFT, geometry);
    }
}
