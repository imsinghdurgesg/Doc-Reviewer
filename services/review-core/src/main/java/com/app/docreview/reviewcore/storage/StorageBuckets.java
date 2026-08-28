package com.app.docreview.reviewcore.storage;

/**
 * Bucket and key layout. Architecture §23.
 *
 * <p>Keys are built here rather than inline at call sites so the layout is stated once. A key
 * assembled ad hoc in three places drifts in two of them, and an object written under a key nobody
 * can reconstruct is unreachable without a bucket scan.
 */
public final class StorageBuckets {

    public static final String STANDARDS = "docreview-standards";
    public static final String USER_DOCUMENTS = "docreview-user-documents";
    public static final String EXPORTS = "docreview-exports";

    private StorageBuckets() {
    }

    /** Pre-validation upload location. Objects here are not yet a standard version. */
    public static String standardIncoming(String standardId, String uploadId, String filename) {
        return "incoming/%s/%s/%s".formatted(standardId, uploadId, filename);
    }

    /** The immutable source of a published version. */
    public static String standardSource(String standardId, String version, String filename) {
        return "active/%s/%s/%s".formatted(standardId, version, filename);
    }

    public static String standardRendered(String standardVersionId) {
        return "rendered/%s/preview.pdf".formatted(standardVersionId);
    }

    public static String standardExtracted(String standardVersionId) {
        return "extracted/%s/document.json".formatted(standardVersionId);
    }

    /**
     * Requirements are keyed by BOTH version and requirement-set version (§12.9): re-extraction
     * publishes a new set without overwriting the one existing comparisons were measured against.
     */
    public static String standardRequirements(String standardVersionId, String requirementSetVersion) {
        return "requirements/%s/%s/requirements.json".formatted(standardVersionId, requirementSetVersion);
    }

    public static String documentUpload(String documentId, String filename) {
        return "uploads/%s/%s".formatted(documentId, filename);
    }

    public static String documentRendered(String documentVersionId) {
        return "rendered/%s/preview.pdf".formatted(documentVersionId);
    }
}
