package com.app.docreview.documentprocessing.rendering;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.documentprocessing.parse.pdf.PdfParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometry enrichment from a rendered PDF. Architecture §11.3, §19.1.
 *
 * <p>The guarantee that matters is IDENTITY PRESERVATION. Span IDs derive from ordinals, and every
 * extracted requirement already cites them (§12.5), so enrichment must change geometry and nothing
 * else. A test that only checked "boxes appeared" would pass while silently renumbering every span
 * and invalidating all stored findings.
 */
class RenderedGeometryEnricherTest {

    private final RenderedGeometryEnricher enricher =
            new RenderedGeometryEnricher(new PdfParser());

    private EvidenceSpan span(String id, String text) {
        return new EvidenceSpan(id, "PROC-V1", null, List.of("3. Access"), 1, 0,
                text.length(), text, "hash-" + id, List.of(), "NODE-1", 1L);
    }

    @Test
    @DisplayName("an absent or empty render leaves the spans untouched")
    void noRenderIsANoOp() {
        var spans = List.of(span("SPAN-a-1-0", "Access must be approved by the data owner."));

        // Geometry is a display convenience: without a render the finding is still valid and citable,
        // so the spans must come back unchanged rather than emptied.
        assertThat(enricher.enrich(null, spans, "PROC-V1", "rend-1", "sha256:abc")).isSameAs(spans);
        assertThat(enricher.enrich(new byte[0], spans, "PROC-V1", "rend-1", "sha256:abc")).isSameAs(spans);
    }

    @Test
    @DisplayName("an unparseable render degrades to no geometry rather than throwing")
    void unparseableRenderIsSafe() {
        var spans = List.of(span("SPAN-a-1-0", "Access must be approved by the data owner."));

        // Runs on the job executor after the 202 (§21 step 5): an escaped exception would kill the
        // worker thread and leave the job non-terminal forever.
        var result = enricher.enrich("not a pdf".getBytes(), spans, "PROC-V1", "rend-1", "sha256:abc");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).spanId()).isEqualTo("SPAN-a-1-0");
        assertThat(result.get(0).boundingBoxes()).isEmpty();
    }

    @Test
    @DisplayName("span identity and content survive enrichment")
    void preservesIdentity() {
        var original = span("SPAN-a-7-0", "Access must be approved by the data owner.");

        // Even when nothing matches, every field except geometry must come back identical — this is
        // what stops enrichment from invalidating requirements that already cite these IDs.
        var result = enricher.enrich("not a pdf".getBytes(), List.of(original), "PROC-V1", "rend-1", "sha256:abc");
        var enriched = result.get(0);

        assertThat(enriched.spanId()).isEqualTo(original.spanId());
        assertThat(enriched.documentVersionId()).isEqualTo(original.documentVersionId());
        assertThat(enriched.exactText()).isEqualTo(original.exactText());
        assertThat(enriched.textHash()).isEqualTo(original.textHash());
        assertThat(enriched.sectionPath()).isEqualTo(original.sectionPath());
        assertThat(enriched.parentNodeId()).isEqualTo(original.parentNodeId());
        assertThat(enriched.ordering()).isEqualTo(original.ordering());
    }

    @Test
    @DisplayName("an empty span list is returned as-is")
    void emptySpans() {
        assertThat(enricher.enrich(new byte[] {1, 2, 3}, List.of(), "PROC-V1", "rend-1", "sha256:abc")).isEmpty();
    }
}
