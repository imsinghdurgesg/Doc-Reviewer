package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.document.CanonicalDocument;
import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.documentprocessing.chunks.SemanticChunker;
import com.app.docreview.documentprocessing.parse.csv.CsvParser;
import com.app.docreview.documentprocessing.parse.docx.DocxParser;
import com.app.docreview.documentprocessing.parse.json.JsonParser;
import com.app.docreview.documentprocessing.parse.pdf.PdfParser;
import com.app.docreview.documentprocessing.parse.text.TextParser;
import com.app.docreview.documentprocessing.rendering.LibreOfficePdfRenderer;
import com.app.docreview.documentprocessing.rendering.RenderedGeometryEnricher;
import com.app.docreview.documentprocessing.spans.PdfSpanGeometrySource;
import com.app.docreview.documentprocessing.spans.SpanBuilder;
import com.app.docreview.documentprocessing.spans.SpanGeometrySource;
import com.app.docreview.documentprocessing.spans.TextHasher;
import com.app.docreview.documentprocessing.storage.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one claimed document-processing job. Architecture §14, §21 steps 5–6.
 *
 * <p>Executes the deterministic pipeline (§39 rule 17 — parse before any LLM is involved; this
 * service never calls one at all, per §8.2):
 *
 * <pre>
 *   load source -> parse -> canonical nodes -> evidence spans -> semantic chunks -> persist
 * </pre>
 *
 * <p>Progress is recorded at each stage (§21) because parsing a large PDF takes minutes and a single
 * "running" state gives no way to tell slow from stuck.
 *
 * <p><strong>Never throws to the caller.</strong> This runs on the job executor after the 202 has
 * already been sent, so there is nobody to throw to: an escaped exception would kill the worker
 * thread and leave the job non-terminal forever. Every failure is converted into a FAILED callback
 * so review-core learns the outcome.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingJobRunner {

    private final ObjectStorage storage;
    private final PdfParser pdfParser;
    private final DocxParser docxParser;
    private final TextParser textParser;
    private final CsvParser csvParser;
    private final JsonParser jsonParser;
    private final LibreOfficePdfRenderer pdfRenderer;
    private final RenderedGeometryEnricher geometryEnricher;
    private final SpanBuilder spanBuilder;
    private final SemanticChunker chunker;
    private final CanonicalArtifactWriter artifactWriter;
    private final WorkerJobRepository jobs;
    private final JobCallbackSender callbackSender;

    /** Executes the job and reports the outcome. Safe to call for a job already terminal. */
    public void run(WorkerJob job) {
        if (job.terminal()) {
            // The recovery sweep re-dispatched work that in fact finished; resend the callback
            // rather than redoing the work (§21).
            log.info("Job {} already terminal ({}); resending callback only",
                    job.getId(), job.getState());
            resendCallback(job);
            return;
        }

        try {
            var result = process(job);

            job.setState(JobState.COMPLETED);
            job.setProgressState("COMPLETED");
            job.setCompletedUnits(result.spanCount());
            job.setTotalUnits(result.spanCount());
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobs.save(job);

            sendCallback(job, JobState.COMPLETED, null, List.of(), Map.of(
                    JobCallback.RESULT_SPAN_COUNT, String.valueOf(result.spanCount()),
                    JobCallback.RESULT_CHUNK_COUNT, String.valueOf(result.chunkCount()),
                    JobCallback.RESULT_PAGE_COUNT, String.valueOf(result.pageCount()),
                    JobCallback.RESULT_CHUNKING_STRATEGY_VERSION, SemanticChunker.STRATEGY_VERSION,
                    // Empty rather than absent when rendering failed: the callback contract is a
                    // Map<String,String>, and review-core treats blank as "no preview".
                    JobCallback.RESULT_RENDERED_OBJECT_KEY,
                    result.renderedObjectKey() == null ? "" : result.renderedObjectKey()));

        } catch (Exception e) {
            // Display-safe reason only. Parser exceptions can quote document content, which must not
            // reach the browser or the log (§34, §10.5).
            String reason = "Document processing failed during " + safeStage(job) + ".";

            job.setState(JobState.FAILED);
            job.setProgressState("FAILED");
            job.setFailureReason(reason);
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobs.save(job);

            log.error("Job {} failed for document version {}: {}",
                    job.getId(), job.getDocumentVersionId(), e.getClass().getSimpleName(), e);

            sendCallback(job, JobState.FAILED, reason, List.of(), Map.of());
        }
    }

    /** Counts produced by a successful run, reported to review-core. */
    private record Result(int spanCount, int chunkCount, int pageCount, String renderedObjectKey) {}

    /**
     * Uniform parser output, so the dispatch below can be a single exhaustive expression.
     *
     * @param geometry supplies per-character-range rectangles when the source itself is a PDF, so
     *                 each sentence-span gets its own boxes rather than the enclosing node's
     */
    private record Parsed(List<CanonicalNode> nodes, String text, int pageCount,
                          SpanGeometrySource geometry) {}

    private Result process(WorkerJob job) throws Exception {
        String versionId = job.getDocumentVersionId();

        advance(job, "PARSING_DOCUMENT");
        byte[] source = storage.get(job.getSourceBucket(), job.getSourceObjectKey());

        // Explicit three-way dispatch on format (§8.2), as a switch EXPRESSION so the compiler
        // enforces exhaustiveness. The previous version branched PDF-or-else-DOCX, so a .txt or .md
        // file — which UploadService accepts — was handed to the DOCX parser and failed on a non-ZIP
        // body. An accepted upload that fails asynchronously reads as a system fault rather than an
        // unsupported file.
        Parsed parsed = switch (formatOf(job.getSourceObjectKey())) {
            case PDF -> {
                var p = pdfParser.parse(source, versionId);
                if (p.noTextLayer()) {
                    // Reported, not thrown: a scanned PDF is a real document that simply yields no
                    // spans. §17.5 depends on this being visible — a comparison over an unparseable
                    // document must reach INSUFFICIENT_EVIDENCE, not MISSING.
                    log.warn("Document version {} has no text layer; spans will be empty", versionId);
                }
                // The source PDF IS the viewer rendition (it is served unmodified), so its own hash
                // is what the browser will load and what the geometry binds to.
                String sourceHash = TextHasher.hashBytes(source);
                yield new Parsed(p.nodes(), p.text(), p.pageCount(),
                        new PdfSpanGeometrySource(p.positions(), p.pageBounds(),
                                job.getSourceObjectKey(), sourceHash));
            }
            // DOCX and text are unpaginated until rendered (§8.2). 0 rather than 1, so the viewer can
            // tell "no pagination" from "one page". Geometry arrives later, from the rendered PDF.
            case DOCX -> {
                var p = docxParser.parse(source, versionId);
                yield new Parsed(p.nodes(), p.text(), 0, SpanGeometrySource.NONE);
            }
            case CSV -> {
                var p = csvParser.parse(source, versionId);
                if (p.nodes().isEmpty()) {
                    // Not a usable table after all (a .csv holding prose, or a single column). Text
                    // parsing still yields citable evidence, which beats an empty document.
                    log.info("CSV {} yielded no table rows; parsing as text", versionId);
                    var t = textParser.parse(source, versionId);
                    yield new Parsed(t.nodes(), t.text(), 0, SpanGeometrySource.NONE);
                }
                yield new Parsed(p.nodes(), p.text(), 0, SpanGeometrySource.NONE);
            }
            case JSON -> {
                var p = jsonParser.parse(source, versionId);
                if (!p.wellFormed() || p.nodes().isEmpty()) {
                    // Malformed JSON is a real upload. Falling back keeps the document comparable
                    // instead of failing the job over a syntax error the reviewer cannot fix here.
                    log.info("JSON {} not well-formed or empty; parsing as text", versionId);
                    var t = textParser.parse(source, versionId);
                    yield new Parsed(t.nodes(), t.text(), 0, SpanGeometrySource.NONE);
                }
                yield new Parsed(p.nodes(), p.text(), 0, SpanGeometrySource.NONE);
            }
            case TEXT -> {
                var p = textParser.parse(source, versionId);
                yield new Parsed(p.nodes(), p.text(), 0, SpanGeometrySource.NONE);
            }
        };

        List<CanonicalNode> nodes = parsed.nodes();
        String text = parsed.text();
        // Not final: a rendered non-PDF source learns its real page count from the rendered layout.
        int pageCount = parsed.pageCount();

        advance(job, "GENERATING_SPANS");
        // Absolute char offsets so highlights resolve against the whole document text (§11.3).
        Map<String, Integer> offsets = charOffsets(nodes, text);
        // Not final: enriched below with geometry from the rendered PDF for non-PDF sources.
        //
        // For a PDF source the geometry source is live here, so each sentence-span is measured
        // against its own character range and marked EXACT. For DOCX/text it is NONE, and geometry
        // arrives from the rendered PDF below.
        List<EvidenceSpan> spans = spanBuilder.build(versionId, nodes,
                node -> offsets.getOrDefault(node.nodeId(), 0), parsed.geometry());

        // §8.2: every non-PDF format is rendered to PDF, so the viewer has ONE path for every
        // document instead of a per-format branch. LibreOffice handles DOCX, TXT, Markdown, and CSV.
        //
        // A PDF source is already renderable and is served directly — re-rendering it would recompress
        // the pages and lose quality for nothing.
        //
        // Done AFTER parsing so a rendering failure cannot cost the extraction results: spans come
        // from the deterministic parse and stay valid without a preview (§11.3).
        String renderedKey = null;
        if (formatOf(job.getSourceObjectKey()) != Format.PDF) {
            advance(job, "RENDERING_DOCUMENT");
            var rendered = pdfRenderer.renderToPdf(source, job.getSourceObjectKey());
            renderedKey = artifactWriter.writeRenderedPreview(
                    versionId, job.getDocumentType(), rendered);

            // A non-PDF source has no coordinates, so its spans carry no bounding boxes and the
            // viewer can show the page but not highlight the evidence on it. The rendered PDF is the
            // only place that geometry exists, so spans are ENRICHED from it here — before chunking
            // and persistence, so the stored spans are the ones the viewer will read.
            //
            // Enriched, never regenerated: span IDs derive from ordinals, so a fresh parse would
            // renumber them and break every citation already indexed (§11.3).
            if (rendered.isPresent()) {
                // The hash of the EXACT bytes stored as the viewer rendition. Geometry derived below
                // is bound to it, so a viewer that later loads a different rendition can tell the
                // rectangles do not apply rather than drawing them against a changed layout.
                String renditionHash = TextHasher.hashBytes(rendered.get());
                spans = geometryEnricher.enrich(
                        rendered.get(), spans, versionId, renderedKey, renditionHash);
                // Page count now known: it comes from the rendered layout, not the source.
                pageCount = pageCountOf(rendered.get());
            }
        }

        advance(job, "GENERATING_CHUNKS");
        var chunks = chunker.chunk(versionId, spans);

        advance(job, "STORING_ARTIFACTS");
        var canonical = new CanonicalDocument(
                versionId,
                DocumentType.valueOf(job.getDocumentType()),
                versionId,
                job.getSourceObjectKey(),
                sha256Placeholder(),
                pageCount,
                nodes);
        artifactWriter.write(canonical, spans, chunks, job.getDocumentType());

        log.info("Processed document version {}: {} nodes, {} spans, {} chunks",
                versionId, nodes.size(), spans.size(), chunks.size());
        return new Result(spans.size(), chunks.size(), pageCount, renderedKey);
    }

    /**
     * Locates each node's text in the document text to give spans absolute offsets.
     *
     * <p>Scans forward from the previous match rather than searching from zero, so repeated
     * boilerplate maps to the occurrence in reading order instead of always the first one.
     */
    private Map<String, Integer> charOffsets(List<CanonicalNode> nodes, String text) {
        Map<String, Integer> offsets = new HashMap<>();
        int cursor = 0;
        for (CanonicalNode node : nodes) {
            if (node.text() == null || node.text().isBlank()) {
                offsets.put(node.nodeId(), cursor);
                continue;
            }
            int found = text.indexOf(node.text(), cursor);
            if (found < 0) {
                // Normalisation during parsing can make a node's text not appear verbatim. Fall back
                // to the cursor rather than -1: a wrong-but-bounded offset degrades a highlight,
                // whereas a negative one would corrupt every downstream span range.
                offsets.put(node.nodeId(), cursor);
            } else {
                offsets.put(node.nodeId(), found);
                cursor = found + node.text().length();
            }
        }
        return offsets;
    }

    private void advance(WorkerJob job, String progressState) {
        job.setProgressState(progressState);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        log.debug("Job {} -> {}", job.getId(), progressState);
    }

    private void sendCallback(WorkerJob job, JobState state, String failureReason,
                              List<String> unitErrors, Map<String, String> results) {
        var callback = new JobCallback(
                job.getOriginJobId(), JobType.DOCUMENT_PROCESSING, job.getIdempotencyKey(),
                state, job.getProgressState(), job.getCompletedUnits(), job.getTotalUnits(),
                failureReason, unitErrors, results);

        if (callbackSender.send(job.getCallbackUrl(), callback)) {
            job.setCallbackDelivered(true);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        }
    }

    private void resendCallback(WorkerJob job) {
        sendCallback(job, job.getState(), job.getFailureReason(), List.of(), Map.of());
    }

    /** Parser families. One per distinct extraction strategy, not one per extension. */
    private enum Format { PDF, DOCX, CSV, JSON, TEXT }

    /**
     * Selects a parser from the object key's extension.
     *
     * <p>Extension rather than declared media type: browsers send inconsistent types for the same
     * file (Markdown arrives as text/plain, text/markdown, or application/octet-stream depending on
     * the OS), whereas the key was written by this application and is stable.
     *
     * <p>Unknown extensions fall to TEXT rather than throwing. UploadService already restricts what
     * can be uploaded (§8.2), so anything reaching here is a supported format; treating an
     * unrecognised one as text degrades to "structure may be poor" instead of failing the job
     * outright. CSV and JSON are handled this way today — their content is readable as text, they
     * simply gain no table or object structure.
     */
    /**
     * Page count of a rendered PDF.
     *
     * <p>Read from the rendered output because that is the pagination the reviewer actually sees. A
     * text or DOCX source has no intrinsic page count, and reporting 0 leaves the viewer unable to
     * show "page 1 of N".
     */
    private int pageCountOf(byte[] pdf) {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            log.debug("Could not read page count from the rendered PDF");
            return 0;
        }
    }

    private Format formatOf(String key) {
        String lower = key == null ? "" : key.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return Format.PDF;
        }
        // `.docx` ONLY. Legacy binary `.doc` is a different format needing POI's HWPF, which is not
        // on the classpath — handing it to the OOXML parser fails on a non-ZIP body. Upload already
        // rejects it (DocumentFormats), so this is defence for an object key written before that:
        // falling through to TEXT extracts something readable instead of failing the job outright.
        if (lower.endsWith(".docx")) {
            return Format.DOCX;
        }
        // Structured formats get structured parsers: a row and a JSON leaf are records, and the prose
        // parser merged each whole file into one unciteable node. Both fall back to TEXT at the call
        // site when the content turns out not to match its extension.
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return Format.CSV;
        }
        if (lower.endsWith(".json")) {
            return Format.JSON;
        }
        return Format.TEXT;
    }

    private String safeStage(WorkerJob job) {
        return job.getProgressState() == null ? "parsing" : job.getProgressState();
    }

    /**
     * Content hash placeholder.
     *
     * <p>review-core already computed and stored the SHA-256 of the source when the version was
     * created, and it is the authoritative value (§11.2). Recomputing it here would be duplicate
     * work whose only possible outcome is disagreeing with the authoritative one. The canonical
     * artifact carries the reference; review-core supplies the hash on read.
     */
    private String sha256Placeholder() {
        return "";
    }
}
