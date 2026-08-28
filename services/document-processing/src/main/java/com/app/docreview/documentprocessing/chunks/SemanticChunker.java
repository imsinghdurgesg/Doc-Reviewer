package com.app.docreview.documentprocessing.chunks;

import com.app.docreview.contracts.document.ChunkTypeClassifier;
import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups evidence spans into retrieval chunks. Architecture §11.4.
 *
 * <p><strong>Structure first, token windows only as a bounded fallback</strong> (§11.4). Chunks
 * follow section boundaries because a requirement and its qualifying sentences live in the same
 * clause — splitting mid-clause puts "The approver must be independent" in one chunk and "of the
 * implementing engineer" in another, and neither retrieves correctly.
 *
 * <p>The fallback exists because structure alone does not bound size: a standard with one 40-page
 * section would otherwise produce a single chunk far past any embedding model's context. Overlap is
 * applied at the split so a requirement spanning the boundary is retrievable from either side.
 *
 * <p>{@code chunkingStrategyVersion} is recorded on every chunk. Changing this logic changes
 * retrieval results, and without the version an old comparison cannot be distinguished from a new
 * one — which is what §19.2's reproducibility guarantee rests on.
 */
@Component
public class SemanticChunker {

    /**
     * Bump on ANY behavioural change here, or previously indexed content becomes unattributable.
     *
     * <p>V2 introduces the eight-type chunk taxonomy. The version carries the taxonomy version with it
     * because a chunk's TYPE changes how it is retrieved and ranked, so a V1 chunk (typed only
     * BODY/CONTROL_SECTION) is not comparable to a V2 chunk over the same text. Combining them means
     * one field identifies both the boundaries and the labels — a comparison that cites
     * {@code SEMANTIC-V2+CHUNKTAX-V1} is reproducible in a way that naming only the boundaries is not.
     */
    public static final String STRATEGY_VERSION =
            "SEMANTIC-V2+" + ChunkTypeClassifier.TAXONOMY_VERSION;

    /** Approximate character budget per chunk. ~4 chars/token is a safe rule of thumb for prose. */
    private static final int MAX_CHUNK_CHARS = 4000;

    /** Carried into the next chunk when a section must be split. */
    private static final int OVERLAP_CHARS = 400;

    public List<SemanticChunk> chunk(String documentVersionId, List<EvidenceSpan> spans) {
        List<SemanticChunk> chunks = new ArrayList<>();
        List<EvidenceSpan> current = new ArrayList<>();
        List<String> currentSection = null;
        int currentChars = 0;
        int index = 0;

        for (EvidenceSpan span : spans) {
            boolean sectionChanged = currentSection != null
                    && !currentSection.equals(span.sectionPath());
            boolean tooLarge = currentChars + span.exactText().length() > MAX_CHUNK_CHARS;

            if (!current.isEmpty() && (sectionChanged || tooLarge)) {
                chunks.add(toChunk(documentVersionId, index++, current, currentSection));

                if (tooLarge && !sectionChanged) {
                    // Same section, split for size: carry trailing spans forward as overlap so a
                    // requirement straddling the boundary is still retrievable.
                    current = tail(current, OVERLAP_CHARS);
                    currentChars = current.stream().mapToInt(s -> s.exactText().length()).sum();
                } else {
                    current = new ArrayList<>();
                    currentChars = 0;
                }
            }

            current.add(span);
            currentChars += span.exactText().length();
            currentSection = span.sectionPath();
        }

        if (!current.isEmpty()) {
            chunks.add(toChunk(documentVersionId, index, current, currentSection));
        }
        return chunks;
    }

    /** Trailing spans whose combined length is within the overlap budget. */
    private List<EvidenceSpan> tail(List<EvidenceSpan> spans, int budget) {
        List<EvidenceSpan> result = new ArrayList<>();
        int used = 0;
        for (int i = spans.size() - 1; i >= 0; i--) {
            int len = spans.get(i).exactText().length();
            if (used + len > budget) {
                break;
            }
            result.add(0, spans.get(i));
            used += len;
        }
        return result;
    }

    private SemanticChunk toChunk(String documentVersionId, int index,
                                  List<EvidenceSpan> spans, List<String> sectionPath) {
        String text = String.join(" ", spans.stream().map(EvidenceSpan::exactText).toList());
        var pages = spans.stream().map(EvidenceSpan::page).filter(java.util.Objects::nonNull).toList();

        return new SemanticChunk(
                SpanIdGenerator.chunkId(documentVersionId, index),
                documentVersionId,
                // Typed from the heading trail and the text (§11.4).
                //
                // Previously this was BODY vs CONTROL_SECTION on nothing more than "does it have a
                // heading" — which told retrieval nothing about whether the chunk could carry an
                // obligation. A definition, a scope statement, and a requirement were all equally
                // eligible to be cited as evidence that a requirement was satisfied.
                ChunkTypeClassifier.classify(sectionPath, text).name(),
                sectionPath == null ? List.of() : sectionPath,
                // The link back to citable evidence: retrieval returns chunks, the model may only
                // cite the spans they were built from (§16.2).
                spans.stream().map(EvidenceSpan::spanId).toList(),
                text,
                pages.isEmpty() ? null : pages.stream().min(Integer::compareTo).orElse(null),
                pages.isEmpty() ? null : pages.stream().max(Integer::compareTo).orElse(null),
                // Approximate: exact tokenization is model-specific and this only needs to bound
                // request size, not be exact.
                Math.max(1, text.length() / 4),
                STRATEGY_VERSION);
    }
}
