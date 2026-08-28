package com.app.docreview.reviewcore.comparison.web;

import com.app.docreview.reviewcore.comparison.application.CanonicalViewService;
import com.app.docreview.reviewcore.comparison.web.dto.CanonicalViewDtos.CanonicalDocumentView;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import com.app.docreview.reviewcore.storage.StorageBuckets;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Streams document bytes for the side-by-side viewer. Architecture §19.1, §7.4.
 *
 * <p>The viewer needs the actual PDF to render pages and place highlight boxes. Without these two
 * routes the workspace shows findings but both document panes stay empty, which reads as a broken
 * page rather than a missing preview.
 *
 * <p><strong>Proxied, never signed.</strong> §7.4 forbids the browser receiving MinIO credentials,
 * and a presigned URL would also bypass the role check below. review-core reads the object and
 * streams it, so every byte passes an authorization boundary.
 *
 * <p>Streamed rather than buffered (§9.1): a standard can be tens of megabytes, and loading it into
 * memory per concurrent viewer is how this service runs out of heap.
 *
 * <p>Serves the ORIGINAL source when no rendered preview exists. §8.2 renders DOCX to PDF for
 * preview, but a source that is already a PDF needs no rendering — and returning it directly means
 * the viewer works today for the common case instead of waiting on the rendering pipeline. A
 * non-PDF source with no rendered preview is a 404 rather than a corrupt download, because PDF.js
 * would fail on the bytes anyway and an explicit absence is easier to act on.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DocumentPreviewController {

    private final UploadedDocumentRepository documents;
    private final StandardVersionRepository versions;
    private final ObjectStorage storage;
    private final CanonicalViewService canonicalView;

    /**
     * The uploaded process document as reflowable nodes, for the HTML review pane (§19.1).
     *
     * <p>Serves the same evidence as {@code /preview} through a different projection: parsed nodes
     * with character offsets instead of rendered pages with rectangles. This is the DEFAULT pane
     * because it needs no rendition — it works for DOCX, CSV, JSON and Markdown, which have no
     * browser-renderable form — and because reflowed text survives zoom without a coordinate system
     * that can drift.
     */
    @GetMapping("/api/uploads/{documentVersionId}/canonical")
    @PreAuthorize("hasRole('REVIEWER')")
    public CanonicalDocumentView uploadCanonical(
            @PathVariable String documentVersionId,
            @RequestParam(defaultValue = "false") boolean includeRepeated) {

        // Existence and ownership are checked against the repository first: the artifact key is
        // derived from the path variable, so reading storage directly would let any version id be
        // probed regardless of whether this service knows the document.
        documents.findByDocumentVersionId(documentVersionId)
                .orElseThrow(() -> ApiException.notFound("Uploaded document"));

        return canonicalView.load(StorageBuckets.USER_DOCUMENTS, documentVersionId, includeRepeated);
    }

    /** A standard version as reflowable nodes (§8.1, §19.1). */
    @GetMapping("/api/standards/{standardVersionId}/canonical")
    @PreAuthorize("hasRole('REVIEWER')")
    public CanonicalDocumentView standardCanonical(
            @PathVariable String standardVersionId,
            @RequestParam(defaultValue = "false") boolean includeRepeated) {

        versions.findById(standardVersionId)
                .orElseThrow(() -> ApiException.notFound("Standard version"));

        return canonicalView.load(StorageBuckets.STANDARDS, standardVersionId, includeRepeated);
    }

    /** The uploaded process document (§14). */
    @GetMapping("/api/uploads/{documentVersionId}/preview")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<InputStreamResource> uploadPreview(
            @PathVariable String documentVersionId) {

        var document = documents.findByDocumentVersionId(documentVersionId)
                .orElseThrow(() -> ApiException.notFound("Uploaded document"));

        // Rendered preview when one exists, otherwise the original.
        String bucket = StorageBuckets.USER_DOCUMENTS;
        String key = document.getRenderedObjectKey() != null
                ? document.getRenderedObjectKey()
                : requireServable(document.getSourceObjectKey(), document.getMediaType(),
                        documentVersionId);

        return stream(bucket, key, document.getFilename(), servableTypeOf(key));
    }

    /** A standard version (§8.1). */
    @GetMapping("/api/standards/{standardVersionId}/preview")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<InputStreamResource> standardPreview(
            @PathVariable String standardVersionId) {

        var version = versions.findById(standardVersionId)
                .orElseThrow(() -> ApiException.notFound("Standard version"));

        String key = version.getRenderedObjectKey() != null
                ? version.getRenderedObjectKey()
                : requireServable(version.getSourceObjectKey(), version.getSourceMediaType(),
                        standardVersionId);

        return stream(StorageBuckets.STANDARDS, key, version.getSourceFilename(),
                servableTypeOf(key));
    }

    /**
     * Whether a source can be served directly, and as what.
     *
     * <p>Extension rather than declared media type: browsers send inconsistent types for the same
     * file, and the extension is what the parsing pipeline already keys its own decision on (see
     * {@code DocumentProcessingJobRunner.formatOf}).
     */
    private MediaType servableTypeOf(String sourceObjectKey) {
        String lower = sourceObjectKey == null ? "" : sourceObjectKey.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        // Text-family sources are still served as text, but only as a FALLBACK for a document whose
        // LibreOffice rendering failed or has not run yet (§8.2 renders every non-PDF format now).
        // Showing the raw text beats an empty pane: the findings cite this very document, and the
        // evidence is verified server-side either way (§16.5).
        //
        // DOCX is deliberately absent: a browser cannot render it and streaming the raw ZIP would
        // trigger a download. Without a rendered preview it is a 404, which the viewer reports.
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".csv") || lower.endsWith(".json")) {
            return MediaType.TEXT_PLAIN;
        }
        return null;
    }

    private String requireServable(String sourceObjectKey, String mediaType, String versionId) {
        if (servableTypeOf(sourceObjectKey) == null) {
            log.debug("No preview available for {} (mediaType={}, key={})",
                    versionId, mediaType, sourceObjectKey);
            throw ApiException.notFound("Rendered preview");
        }
        return sourceObjectKey;
    }

    private ResponseEntity<InputStreamResource> stream(String bucket, String key, String filename,
                                                      MediaType contentType) {
        var body = new InputStreamResource(storage.stream(bucket, key));

        return ResponseEntity.ok()
                // The ACTUAL type. Declaring application/pdf for a text file makes PDF.js fail on
                // bytes that were never a PDF, reporting a parse error instead of the real mismatch.
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType)
                // inline, not attachment: this is rendered in a viewer pane, and `attachment` makes
                // the browser download it instead.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + sanitize(filename) + "\"")
                // Private: this is confidential document content, so no shared cache may hold it.
                // Immutable within the window because a document version's bytes never change (§19.2).
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePrivate())
                .body(body);
    }

    /** Strips quotes and control characters that would break the Content-Disposition header. */
    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document.pdf";
        }
        return filename.replaceAll("[\"\\\\\\r\\n]", "_");
    }
}
