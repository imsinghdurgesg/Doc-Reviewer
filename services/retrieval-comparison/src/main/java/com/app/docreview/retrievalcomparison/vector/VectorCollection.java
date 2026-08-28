package com.app.docreview.retrievalcomparison.vector;

/**
 * The three Qdrant collections. Architecture §13.2.
 *
 * <p>Separate collections rather than one with a type filter, for three reasons:
 *
 * <ul>
 *   <li>They answer different questions and are searched independently — "which standard?" never
 *       needs to see process chunks.</li>
 *   <li>Process vectors are subject to document retention and get deleted (§13.2); standard vectors
 *       are not. Mixing them makes deletion a filtered scan instead of a collection drop.</li>
 *   <li>An embedding-model change requires a new collection (§13.4). Versioned names make that a
 *       create-and-switch rather than a migration.</li>
 * </ul>
 *
 * <p>The {@code _v1} suffix is the index schema version. It changes when the payload shape or the
 * embedding model changes — never silently.
 */
public enum VectorCollection {

    /** Which standards are relevant to the uploaded document? (§15.1) */
    STANDARD_DOCUMENTS("docreview_standard_documents_v1"),

    /** Which requirements of a selected standard are relevant? (§15.2) */
    STANDARD_REQUIREMENTS("docreview_standard_requirements_v1"),

    /**
     * Which uploaded-document passages are relevant to a requirement?
     *
     * <p>Subject to retention: vectors here are deleted with the document (§13.2), so a comparison
     * whose document has expired cannot be silently re-run against stale content.
     */
    PROCESS_CHUNKS("docreview_process_chunks_v1");

    private final String collectionName;

    VectorCollection(String collectionName) {
        this.collectionName = collectionName;
    }

    public String collectionName() {
        return collectionName;
    }
}
