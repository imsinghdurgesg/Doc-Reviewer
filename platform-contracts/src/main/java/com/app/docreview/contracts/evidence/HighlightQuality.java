package com.app.docreview.contracts.evidence;

/**
 * How precisely a span's stored rectangles locate its text. Architecture §11.3, §19.1.
 *
 * <p><strong>Why this is stored rather than inferred in the viewer.</strong> A non-empty box list is
 * not the same claim as "this is where the sentence is". Boxes can bound the containing paragraph
 * rather than the cited sentence, or a page with no rectangle at all. The viewer cannot tell those
 * apart by looking at the geometry, so the producer records what it actually achieved and the UI
 * labels the highlight honestly (§13.2 of the highlighting analysis).
 *
 * <p>Drawing an approximate rectangle without saying so is the failure this enum exists to prevent:
 * a reviewer who is told the evidence is at a location, and finds unrelated text there, has no way
 * to know whether the tool or the document is wrong.
 */
public enum HighlightQuality {

    /**
     * Rectangles bound exactly the span's own characters.
     *
     * <p>Produced when the geometry came from the same parse that produced the span — a native PDF,
     * where glyph positions for this character range were read directly.
     */
    EXACT,

    /**
     * Rectangles bound a region that CONTAINS the span's text but is wider than it.
     *
     * <p>Typically the enclosing paragraph or node: the span was located, but at node granularity
     * rather than per-sentence. The highlight is trustworthy as a pointer and misleading as a
     * boundary, so the UI marks it approximate.
     */
    APPROXIMATE,

    /**
     * The page is known; no usable rectangles exist.
     *
     * <p>The viewer can navigate to the page and must not draw a box. Distinct from
     * {@link #UNRESOLVED} because "we know the page" is materially more useful to a reviewer than
     * "we know nothing", and the two would otherwise be indistinguishable — both have empty box
     * lists.
     */
    PAGE_ONLY,

    /**
     * No verified visual location.
     *
     * <p>The span's text is still valid and citable — it is hydrated from storage and hash-checked —
     * but it could not be located in the rendition. The UI shows the extracted text and offers the
     * original document rather than drawing an unverified highlight.
     */
    UNRESOLVED
}
