package com.app.docreview.contracts.document;

import com.app.docreview.contracts.evidence.BoundingBox;

import java.util.List;

/**
 * One structural element of a parsed document. Architecture §11.2.
 *
 * <p><strong>Documents are never flattened to plain text</strong> (§11.2). A blob cannot answer
 * "which page, which clause, where on the page" — and without those, evidence cannot be highlighted
 * or audited. Every node keeps its position.
 *
 * <p>{@code ordinal} is explicit rather than implied by list position: reading order is not the same
 * as extraction order once tables, footnotes, and multi-column layouts are involved, and a
 * downstream sort must not depend on how the parser happened to emit nodes.
 *
 * <p>{@code textHash} detects accidental mutation (§11.3). If a re-parse changes a node's text, the
 * hash changes and stale evidence referring to it becomes detectable rather than silently wrong.
 *
 * @param sectionPath heading trail, outermost first — the reviewer-facing location of this node
 * @param repeated    true for content appearing at the same position on many pages; excluded from
 *                    retrieval by default (§11.2)
 */
public record CanonicalNode(
        String nodeId,
        NodeType nodeType,
        List<String> sectionPath,
        Integer page,
        Integer paragraph,
        int ordinal,
        String text,
        String textHash,
        List<BoundingBox> boundingBoxes,
        boolean repeated
) {}
