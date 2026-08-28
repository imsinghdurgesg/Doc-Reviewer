package com.app.docreview.reviewcore.upload.web;

import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.UploadResponse;
import com.app.docreview.reviewcore.security.CurrentUser;
import com.app.docreview.reviewcore.upload.application.UploadService;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Process-document uploads. Architecture §8.1, §14.
 *
 * <p>Returns 202 rather than 201: the document is stored but parsing runs asynchronously (§21), so
 * the resource is accepted rather than complete. The UI polls the returned version for
 * {@code readyForComparison} before offering to start a review.
 *
 * <p>Any REVIEWER may upload — reviewing a document is the product's primary action, not an
 * administrative one. Standards administration is separately restricted (§33).
 */
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;
    private final CurrentUser currentUser;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        var document = uploadService.upload(file, currentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(document));
    }

    /** Polled by the UI while parsing runs. */
    @GetMapping("/{documentVersionId}")
    @PreAuthorize("hasRole('REVIEWER')")
    public UploadResponse get(@PathVariable String documentVersionId) {
        return toResponse(uploadService.get(documentVersionId));
    }

    @GetMapping
    @PreAuthorize("hasRole('REVIEWER')")
    public List<UploadResponse> recent() {
        return uploadService.recent().stream().map(this::toResponse).toList();
    }

    private UploadResponse toResponse(UploadedDocument document) {
        return new UploadResponse(
                document.getDocumentVersionId(),
                document.getFilename(),
                document.getMediaType(),
                document.getSizeBytes(),
                document.getState().name(),
                document.getStateMessage(),
                document.getPageCount(),
                document.getSpanCount(),
                document.readyForComparison(),
                document.getCreatedAt());
    }
}
