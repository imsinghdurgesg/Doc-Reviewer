package com.app.docreview.reviewcore.upload.application;

import com.app.docreview.reviewcore.shared.error.ApiException;

import java.util.List;
import java.util.Locale;

/**
 * The document formats the pipeline can parse. Architecture §8.2.
 *
 * <p>One definition, shared by process uploads and standard uploads. Previously the list lived inline
 * in {@code UploadService} and standards had no check at all, so a standard of any type was accepted
 * and failed later during parsing — and §8.1 refuses to activate a FAILED version, leaving an
 * administrator with a broken standard and no stated reason.
 *
 * <p>Kept in step with {@code DocumentProcessingJobRunner.formatOf}: PDF and DOCX have dedicated
 * parsers, and everything else is read as text. Advertising a format with no parser produces an
 * upload that succeeds and then fails asynchronously, which reads as a system fault rather than an
 * unsupported file.
 */
public final class DocumentFormats {

    /**
     * Extensions accepted by both upload paths.
     *
     * <p><strong>{@code .doc} is deliberately absent.</strong> Legacy binary Word is a different
     * format from {@code .docx}: it needs POI's HWPF (poi-scratchpad), and only poi-ooxml is on the
     * classpath. Accepting it meant the file passed validation, rendered fine through LibreOffice,
     * and then produced no spans — a document that displays but has no citable evidence, which reads
     * as a system fault rather than an unsupported format. Rejecting at upload names the real
     * problem; re-add this only alongside a parser that can read it.
     */
    public static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".pdf", ".docx", ".txt", ".md", ".markdown", ".csv", ".json");

    private DocumentFormats() {
    }

    public static boolean isSupported(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Throws a display-safe 400 when the format has no parser.
     *
     * <p>Rejecting at upload rather than during processing is deliberate: an immediate error names
     * the real problem, whereas an async failure looks like the system broke.
     */
    public static void requireSupported(String filename) {
        if (!isSupported(filename)) {
            throw ApiException.badRequest("UNSUPPORTED_MEDIA_TYPE",
                    "Unsupported file type. Accepted formats: PDF, DOCX, TXT, Markdown, CSV, JSON.");
        }
    }
}
