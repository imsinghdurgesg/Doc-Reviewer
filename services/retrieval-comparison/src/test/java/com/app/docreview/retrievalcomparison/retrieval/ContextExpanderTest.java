package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.retrievalcomparison.retrieval.expansion.ContextExpander;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bounded context expansion. Architecture §15.3. */
class ContextExpanderTest {

    private final ContextExpander expander = new ContextExpander();

    private EvidenceSpan span(String id, String text, String section) {
        return new EvidenceSpan(id, "DOC-V1", 1, List.of(section), 1, 0, text.length(),
                text, "sha256:x", List.of(), "NODE", 1);
    }

    @Test
    @DisplayName("the parent heading is included: it changes how a passage reads")
    void includesParentHeading() {
        // "Emergency Changes" above a passage changes the meaning of every sentence under it.
        var spans = List.of(span("S1", "An engineer may deploy immediately.", "6 Emergency Changes"));
        assertThat(expander.expand(spans, List.of("S1"))).contains("6 Emergency Changes");
    }

    @Test
    @DisplayName("an adjacent sentence in the same section is included")
    void includesSameSectionNeighbour() {
        // The qualifier is frequently the sentence next door: "the approver signs off" means little
        // without "a second engineer who did not write the change".
        var spans = List.of(
                span("S1", "A second engineer reviews the evidence.", "4 Approval"),
                span("S2", "The approver signs off in the ticket.", "4 Approval"));
        assertThat(expander.expand(spans, List.of("S2"))).contains("A second engineer reviews");
    }

    @Test
    @DisplayName("a neighbour from a different section is excluded")
    void excludesCrossSectionNeighbour() {
        // Adjacent in the file, unrelated in meaning. Including it invites the model to read across
        // a boundary the author drew deliberately.
        var spans = List.of(
                span("S1", "Rollback plans must be documented.", "5 Rollback"),
                span("S2", "Emergency changes may bypass approval.", "6 Emergency Changes"));
        assertThat(expander.expand(spans, List.of("S2"))).doesNotContain("Rollback plans");
    }

    @Test
    @DisplayName("context is length-bounded, so expansion cannot become 'send the document'")
    void boundsContext() {
        // §15.5 forbids putting a whole document in one prompt; unbounded expansion arrives there
        // gradually.
        String long1 = "x".repeat(2000);
        var spans = List.of(
                span("S1", long1, "4 Approval"),
                span("S2", "core passage", "4 Approval"),
                span("S3", long1, "4 Approval"));
        assertThat(expander.expand(spans, List.of("S2")).length()).isLessThanOrEqualTo(900);
    }

    @Test
    @DisplayName("an unknown span id yields no context rather than throwing")
    void unknownSpanIsSafe() {
        var spans = List.of(span("S1", "text", "4 Approval"));
        assertThat(expander.expand(spans, List.of("MISSING"))).isNull();
    }
}
