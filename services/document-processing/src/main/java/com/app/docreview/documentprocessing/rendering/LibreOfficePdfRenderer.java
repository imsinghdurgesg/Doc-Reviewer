package com.app.docreview.documentprocessing.rendering;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Renders any non-PDF source to PDF with LibreOffice headless. Architecture §5.3, §8.2.
 *
 * <p>Used for DOCX, TXT, Markdown, and CSV so the review workspace has ONE viewer path for every
 * document. A format-specific viewer per source type means highlight positioning, page navigation,
 * and zoom all need a second implementation — and the two drift.
 *
 * <p>PDF sources are NOT passed through here: they are already renderable, and re-rendering would
 * recompress the pages and degrade quality for no gain.
 *
 * <p>An OS package rather than a Maven dependency, because there is no JVM implementation of this
 * conversion. The binary and its fonts are installed in the runtime image (see the Dockerfile).
 *
 * <p><strong>Fonts matter more than they look.</strong> The rendered PDF is what the reviewer sees,
 * and highlight boxes come from the ORIGINAL parse. If LibreOffice substitutes a metrically different
 * face, text reflows and every highlight lands in the wrong place — a failure that reads as a
 * coordinate bug rather than a missing font. The image installs DejaVu and Liberation for this
 * reason.
 *
 * <p>Each conversion runs in a private profile directory. LibreOffice keeps a per-user profile with a
 * lock file; concurrent invocations sharing one profile serialise at best and corrupt it at worst,
 * and this service runs conversions on a bounded pool (§21) so concurrency is expected.
 *
 * <p>Failure is reported, never thrown past the caller. A preview is a convenience: §11.3 evidence
 * spans come from the deterministic parse and remain valid without it. Failing the whole processing
 * job because a preview could not be produced would discard usable extraction results.
 */
@Component
@Slf4j
public class LibreOfficePdfRenderer {

    /**
     * Conversion timeout. LibreOffice can hang on a malformed document rather than exit, and an
     * unbounded wait would occupy a worker thread permanently.
     */
    private final Duration timeout;

    /** Absolute path to the binary, so a PATH difference between images fails loudly at startup. */
    private final String executable;

    public LibreOfficePdfRenderer(
            @Value("${docreview.parsing.libreoffice-timeout:120s}") Duration timeout,
            @Value("${docreview.parsing.libreoffice-path:soffice}") String executable) {
        this.timeout = timeout;
        this.executable = executable;
    }

    /**
     * Renders {@code source} to PDF.
     *
     * @return the PDF bytes, or empty when conversion failed or produced nothing
     */
    public Optional<byte[]> renderToPdf(byte[] source, String filename) {
        Path work = null;
        try {
            work = Files.createTempDirectory("docreview-render-");
            // Keep the original extension: LibreOffice selects its import filter from it, and a
            // generic name makes it guess — usually wrongly for OOXML.
            Path input = work.resolve(safeName(filename));
            Files.write(input, source);

            Path profile = work.resolve("profile");
            Files.createDirectories(profile);

            var command = List.of(
                    executable,
                    "--headless",
                    "--norestore",
                    // No lock file and no recovery dialog; both block a non-interactive run.
                    "--nolockcheck",
                    "--nodefault",
                    "--nofirststartwizard",
                    // Private profile per conversion. See the class comment on concurrency.
                    "-env:UserInstallation=file://" + profile.toAbsolutePath(),
                    // Export filter must match the application that LOADS the file, not a fixed one.
                    // A .csv opens in Calc, and naming Writer's filter for it fails outright with
                    // "source file could not be loaded" — the document then has no rendition and no
                    // geometry, so evidence can never be highlighted.
                    "--convert-to", exportFilterFor(input.getFileName().toString()),
                    "--outdir", work.toAbsolutePath().toString(),
                    input.toAbsolutePath().toString());

            var process = new ProcessBuilder(command)
                    // Merge stderr so a font or filter warning appears in the same captured stream.
                    .redirectErrorStream(true)
                    .start();

            // Drain the output. A process whose pipe buffer fills blocks forever, which would turn
            // the timeout below into the only thing that ever ends the conversion.
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("LibreOffice conversion of {} exceeded {}; abandoned", filename, timeout);
                return Optional.empty();
            }

            if (process.exitValue() != 0) {
                // Output only, never the document bytes (§10.5).
                log.warn("LibreOffice exited {} converting {}: {}",
                        process.exitValue(), filename, truncate(output));
                return Optional.empty();
            }

            // LibreOffice names the result after the input with a .pdf extension, but the exact
            // stem can differ (spaces, unicode), so the directory is searched rather than assumed.
            return findPdf(work, input);

        } catch (IOException e) {
            log.warn("LibreOffice conversion of {} failed: {}", filename, e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            // Restore the flag so a shutdown in progress is not swallowed.
            Thread.currentThread().interrupt();
            log.warn("LibreOffice conversion of {} was interrupted", filename);
            return Optional.empty();
        } finally {
            deleteQuietly(work);
        }
    }

    private Optional<byte[]> findPdf(Path work, Path input) throws IOException {
        try (Stream<Path> files = Files.list(work)) {
            Optional<Path> pdf = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> !p.equals(input))
                    // Largest wins if several exist; a zero-byte artifact is a failed export.
                    .max(Comparator.comparingLong(p -> p.toFile().length()));

            if (pdf.isEmpty()) {
                log.warn("LibreOffice reported success but produced no PDF for {}",
                        input.getFileName());
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(pdf.get());
            if (bytes.length == 0) {
                return Optional.empty();
            }
            log.info("Rendered {} to PDF ({} bytes)", input.getFileName(), bytes.length);
            return Optional.of(bytes);
        }
    }

    /**
     * Strips path separators and control characters from an untrusted filename.
     *
     * <p>The name comes from a user upload and is used to build a path. Without this, a name
     * containing {@code ../} would write outside the temp directory.
     */
    private String safeName(String filename) {
        String base = filename == null || filename.isBlank() ? "document.txt" : filename;
        // Strip any directory component: the key arrives as "uploads/PROC-x/name.docx" and only the
        // final segment should become the temp filename.
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        String name = slash >= 0 ? base.substring(slash + 1) : base;
        String cleaned = name.replaceAll("[\\r\\n\\t]", "_").strip();

        // The EXTENSION is what selects LibreOffice's import filter, so it must be preserved rather
        // than forced: a .md renamed to .docx makes LibreOffice try the OOXML filter on plain text
        // and fail. Only a missing extension gets a default.
        return cleaned.contains(".") ? cleaned : cleaned + ".txt";
    }

    /**
     * The PDF export filter for the application that will load this file.
     *
     * <p>LibreOffice picks its IMPORT filter from the extension, but the export filter is whatever
     * {@code --convert-to} names — and the two must belong to the same application. A spreadsheet
     * loaded by Calc cannot be exported by {@code writer_pdf_Export}; the run fails before producing
     * anything, which reads as a broken renderer rather than a mismatched filter.
     *
     * <p>Everything else here is text or a word-processor document, so Writer remains the default.
     */
    private String exportFilterFor(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")
                || lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return "pdf:calc_pdf_Export";
        }
        return "pdf:writer_pdf_Export";
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 300 ? single.substring(0, 300) + "…" : single;
    }

    private void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            // Depth-first so directories are removed after their contents.
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete {}", p);
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean render directory {}", directory);
        }
    }
}
