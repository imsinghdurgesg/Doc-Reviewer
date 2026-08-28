package com.app.docreview.reviewcore.comparison.web.dto;

import com.app.docreview.contracts.document.NodeType;

import java.util.List;

/**
 * The reflowable view of a document version, for the HTML review pane. Architecture §11.2, §19.1.
 *
 * <p><strong>Why this exists alongside the PDF preview.</strong> The PDF pane renders a fixed page
 * and places rectangles on it; that is authoritative for the controlled copy but requires a rendered
 * PDF, a matching rendition hash, and a coordinate system that survives zoom and device pixel ratio.
 * The HTML pane needs none of those: it renders parsed nodes and highlights a character range inside
 * one of them. It therefore works for every document type — including the DOCX, CSV, JSON and
 * Markdown sources that have no usable browser rendition — and it reflows.
 *
 * <p><strong>What makes it safe.</strong> Highlighting here is a LOOKUP, not a search. An evidence
 * span carries {@code parentNodeId} plus a document-absolute character range; a node carries its own
 * {@code charStart}. Subtracting one from the other gives the range to mark. Nothing re-locates the
 * model's words in the document, which is the fuzzy-matching step §16.4 exists to make unnecessary —
 * and the step that, done wrong, silently highlights the wrong sentence with full confidence.
 */
public final class CanonicalViewDtos {

    private CanonicalViewDtos() {
    }

    /**
     * One structural element, positioned in the document's character stream.
     *
     * <p>{@code charStart} is the field {@code CanonicalNode} does not carry: the parser computes it
     * while building spans (see {@code SpanBuilder}, where {@code spanStart = nodeStart + range[0]})
     * but the stored node keeps only its text. Recomputing it on read — by concatenating node text in
     * order — is what makes span offsets resolvable client-side without shipping the whole document
     * text twice.
     *
     * <p>{@code textHash} travels so the viewer can verify the node it is about to highlight is the
     * one the span was measured against. This is the HTML pane's counterpart to
     * {@code EvidenceSpan.geometryAppliesTo(...)}: on a mismatch the highlight is suppressed rather
     * than drawn at a shifted offset, so a re-parse becomes visible instead of silently wrong.
     *
     * @param page      1-based source page, or null for a source with no pagination. Kept even though
     *                  HTML reflow destroys page layout, because a reviewer cites "clause 4.2,
     *                  page 14" and needs the number the printed document actually uses.
     * @param ordinal   reading order, which is NOT list order once tables and multi-column layouts
     *                  are involved (§11.2). Clients sort on this.
     * @param charStart offset of this node's text within the document's character stream; the basis
     *                  for turning a span's absolute range into a node-relative one
     */
    public record CanonicalNodeView(
            String nodeId,
            NodeType nodeType,
            List<String> sectionPath,
            Integer page,
            Integer paragraph,
            int ordinal,
            String text,
            String textHash,
            int charStart,
            int charEnd) {
    }

    /**
     * A document version's nodes in reading order.
     *
     * @param documentVersionId the version these nodes belong to; a viewer holding evidence for a
     *                          different version must not highlight against them
     * @param contentHash       ties these nodes to the exact source bytes they were parsed from
     *                          (§11.2). The analogue of {@code renditionSha256} for the HTML pane:
     *                          evidence hydrated from a different parse of the same document is
     *                          detectable rather than silently misaligned.
     * @param pages             page count of the source, for the page-break markers the HTML pane
     *                          draws so pagination is not lost entirely
     * @param nodes             reading-order nodes, repeated content already removed by default
     */
    public record CanonicalDocumentView(
            String documentVersionId,
            String title,
            String contentHash,
            int pages,
            List<CanonicalNodeView> nodes) {
    }
}
