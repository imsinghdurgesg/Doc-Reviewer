package com.app.docreview.contracts.document;

/**
 * Structural role of a canonical node. Architecture §11.2.
 *
 * <p>{@link #PAGE_HEADER} and {@link #PAGE_FOOTER} exist to be EXCLUDED. A running header repeated
 * on every page ("Technology Change Management Standard — Confidential") is lexically similar to
 * real content and would dominate retrieval: every query matches it, on every page. §11.2 requires
 * repeated headers and footers to be marked so semantic retrieval can skip them by default.
 */
public enum NodeType {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    FOOTNOTE,
    CAPTION,
    PAGE_HEADER,
    PAGE_FOOTER,
    CODE_BLOCK
}
