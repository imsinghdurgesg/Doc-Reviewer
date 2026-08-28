package com.app.docreview.reviewcore.upload.application;

import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import com.app.docreview.reviewcore.storage.StorageBuckets;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Accepts process-document uploads. Architecture §8.1, §14.
 *
 * <p>The order of operations matters and is the same discipline {@code StandardAdminService} uses:
 * hash, then check for a duplicate, then store, then create the version, then dispatch. Hashing
 * before storing means a re-submitted file costs no write; dispatching last means the durable record
 * exists before any worker can call back about it (§21).
 *
 * <p><strong>Validation happens here AND in the worker.</strong> This service checks what it can
 * cheaply (emptiness, size, extension); {@code document-processing} verifies the file signature and
 * enforces extraction limits (§8.2). Neither is redundant — this one gives the user an immediate,
 * actionable error, while the worker's check is the one that holds against a renamed file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final UploadedDocumentRepository documents;
    private final ObjectStorage storage;
    private final JobDispatcher jobDispatcher;

    /**
     * Stores an uploaded document and queues processing.
     *
     * @return the document version, existing when the identical file was already uploaded
     */
    public UploadedDocument upload(MultipartFile file, String actor) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "The uploaded file is empty.");
        }

        String filename = file.getOriginalFilename() == null
                ? "document" : file.getOriginalFilename();
        requireSupportedExtension(filename);

        byte[] content = readAll(file);
        String sha256 = sha256Hex(content);

        // Identical content is the same document. Returning the existing version makes a
        // double-submitted form a no-op rather than a second parse and a second set of spans.
        var duplicate = documents.findByContentSha256(sha256);
        if (duplicate.isPresent()) {
            log.info("Re-upload of identical content; returning existing version {}",
                    duplicate.get().getDocumentVersionId());
            return duplicate.get();
        }

        // Application-assigned so it is meaningful in a job log and a Qdrant payload, rather than a
        // Mongo ObjectId that means nothing outside this collection.
        String documentVersionId = "PROC-" + UUID.randomUUID().toString().substring(0, 12);
        String objectKey = StorageBuckets.documentUpload(documentVersionId, filename);

        storage.put(StorageBuckets.USER_DOCUMENTS, objectKey, content,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType());

        var document = documents.save(new UploadedDocument(
                documentVersionId, filename, file.getContentType(), content.length,
                sha256, StorageBuckets.USER_DOCUMENTS, objectKey, actor));

        // Dispatched AFTER the record exists, so a completion callback always has a document to
        // attribute itself to (§21).
        jobDispatcher.dispatchDocumentProcessing(
                documentVersionId, DocumentType.UPLOADED_PROCESS,
                StorageBuckets.USER_DOCUMENTS, objectKey, file.getContentType());

        log.info("Uploaded document {} ({}, {} bytes) and queued processing",
                documentVersionId, filename, content.length);
        return document;
    }

    public UploadedDocument get(String documentVersionId) {
        return documents.findByDocumentVersionId(documentVersionId)
                .orElseThrow(() -> ApiException.notFound("Uploaded document"));
    }

    public List<UploadedDocument> recent() {
        return documents.findTop50ByOrderByCreatedAtDesc();
    }

    private void requireSupportedExtension(String filename) {
        // Shared with the standards upload path, so the two cannot drift (§8.2).
        DocumentFormats.requireSupported(filename);
    }

    private byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UPLOAD_READ_FAILED", "Could not read the uploaded file.");
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable outside a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
