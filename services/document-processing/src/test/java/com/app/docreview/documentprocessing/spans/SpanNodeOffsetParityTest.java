package com.app.docreview.documentprocessing.spans;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTML review pane resolves highlights the same way the PDF pane does. Architecture §19.1.
 *
 * <p><strong>What this pins down.</strong> The PDF pane highlights by drawing a span's stored
 * rectangles. The HTML pane has no rectangles; it locates the span's character range inside the node
 * that contains it, and marks that range. Both must select the SAME text, or the two views of one
 * finding disagree about what the evidence says — and the HTML one, being the default, would be the
 * wrong one a reviewer sees.
 *
 * <p>The resolution the viewer performs is a lookup, never a search:
 *
 * <pre>
 *   node  = nodesById[span.parentNodeId()]
 *   start = span.charStart() - nodeOffsets[node.nodeId()]
 *   end   = span.charEnd()   - nodeOffsets[node.nodeId()]
 *   node.text().substring(start, end)   ==  span.exactText()
 * </pre>
 *
 * <p>That last equality is the whole contract. It holds because {@code SpanBuilder} derives every
 * span as {@code nodeStart + rangeWithinNode}, so subtracting {@code nodeStart} inverts it exactly.
 * Practice Lens's viewer, by contrast, token-matches the model's quotation against paragraphs and
 * highlights the best scorer — which always returns something, so a wrong match is invisible. These
 * assertions exist to keep this codebase on the arithmetic side of that line.
 */
class SpanNodeOffsetParityTest {

    private final SpanBuilder spanBuilder = new SpanBuilder();

    /**
     * Mirrors {@code DocumentProcessingJobRunner.charOffsets}, which is what production uses and what
     * {@code /internal/documents/{id}/node-offsets} reproduces. Kept here so the test exercises the
     * same forward-scanning behaviour rather than an idealised one — in particular, repeated
     * boilerplate must map to its occurrence in reading order, not always to the first.
     */
    private static Map<String, Integer> charOffsets(List<CanonicalNode> nodes, String text) {
        Map<String, Integer> offsets = new HashMap<>();
        int cursor = 0;
        for (CanonicalNode node : nodes) {
            if (node.text() == null || node.text().isBlank()) {
                offsets.put(node.nodeId(), cursor);
                continue;
            }
            int found = text.indexOf(node.text(), cursor);
            if (found < 0) {
                offsets.put(node.nodeId(), cursor);
            } else {
                offsets.put(node.nodeId(), found);
                cursor = found + node.text().length();
            }
        }
        return offsets;
    }

    private static CanonicalNode node(String id, NodeType type, int ordinal, String text) {
        return new CanonicalNode(id, type, List.of("4. Change Management"), 1, ordinal, ordinal,
                text, "sha256:" + Integer.toHexString(text.hashCode()), List.of(), false);
    }

    /** Joins node text the way a parser's extracted text runs, so offsets are realistic. */
    private static String documentText(List<CanonicalNode> nodes) {
        return String.join("\n\n", nodes.stream().map(CanonicalNode::text).toList());
    }

    @Test
    @DisplayName("every span's absolute range, made node-relative, selects exactly its own text")
    void nodeRelativeRangeSelectsTheSpanText() {
        var nodes = List.of(
                node("n1", NodeType.HEADING, 0, "4.2 Approval"),
                node("n2", NodeType.PARAGRAPH, 1,
                        "Changes must be approved before deployment. "
                                + "The approver must be independent of the implementing engineer. "
                                + "Emergency changes may be approved retrospectively within one business day."),
                node("n3", NodeType.PARAGRAPH, 2,
                        "Approval evidence is retained for seven years in the change record."));

        String text = documentText(nodes);
        var offsets = charOffsets(nodes, text);
        var spans = spanBuilder.build("DOC-V1", nodes, n -> offsets.getOrDefault(n.nodeId(), 0));

        assertThat(spans).isNotEmpty();

        Map<String, CanonicalNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.nodeId(), n));

        for (EvidenceSpan span : spans) {
            CanonicalNode parent = byId.get(span.parentNodeId());
            assertThat(parent)
                    .as("span %s names a node that exists; an unresolvable parent is a highlight "
                            + "the HTML pane can never place", span.spanId())
                    .isNotNull();

            int nodeStart = offsets.get(parent.nodeId());
            int start = span.charStart() - nodeStart;
            int end = span.charEnd() - nodeStart;

            assertThat(start)
                    .as("span %s starts within its node", span.spanId())
                    .isBetween(0, parent.text().length());
            assertThat(end)
                    .as("span %s ends within its node", span.spanId())
                    .isBetween(start, parent.text().length());

            // The contract. `exactText` is stripped by SpanBuilder, so the substring is compared
            // stripped too — the range still bounds the sentence including its trailing space.
            assertThat(parent.text().substring(start, end).strip())
                    .as("HTML pane marks exactly what the span cites")
                    .isEqualTo(span.exactText());
        }
    }

    @Test
    @DisplayName("a sentence span selects one sentence, not the containing paragraph")
    void sentenceSpanIsNotWidenedToTheParagraph() {
        var paragraph = node("n1", NodeType.PARAGRAPH, 0,
                "Changes must be approved before deployment. "
                        + "The approver must be independent of the implementing engineer.");
        var nodes = List.of(paragraph);
        var offsets = charOffsets(nodes, documentText(nodes));
        var spans = spanBuilder.build("DOC-V1", nodes, n -> offsets.getOrDefault(n.nodeId(), 0));

        // Two sentences, two citable spans. One span covering the whole paragraph would highlight the
        // independence rule for a finding about approval timing, and vice versa — the over-citation
        // that makes a highlight useless as proof.
        assertThat(spans).hasSize(2);
        assertThat(spans).extracting(EvidenceSpan::exactText).containsExactly(
                "Changes must be approved before deployment.",
                "The approver must be independent of the implementing engineer.");

        int nodeStart = offsets.get("n1");
        EvidenceSpan second = spans.get(1);
        assertThat(paragraph.text().substring(
                second.charStart() - nodeStart, second.charEnd() - nodeStart).strip())
                .isEqualTo("The approver must be independent of the implementing engineer.");
    }

    @Test
    @DisplayName("repeated headers produce no span, so the HTML pane has nothing to highlight there")
    void repeatedContentIsNotCitable() {
        var header = new CanonicalNode("h1", NodeType.PARAGRAPH, List.of(), 1, 0, 0,
                "ACME Internal — Change Management Standard v3", "sha256:h", List.of(), true);
        var body = node("n1", NodeType.PARAGRAPH, 1,
                "Changes must be approved before deployment by an independent reviewer.");

        var nodes = List.of(header, body);
        var offsets = charOffsets(nodes, documentText(nodes));
        var spans = spanBuilder.build("DOC-V1", nodes, n -> offsets.getOrDefault(n.nodeId(), 0));

        assertThat(spans).isNotEmpty();
        assertThat(spans).extracting(EvidenceSpan::parentNodeId).doesNotContain("h1");
    }

    /**
     * The node-offsets endpoint derives each node's start from its earliest span, and the client
     * solves {@code nodeStart = charStart - indexOf(spanText)}. This asserts that derivation returns
     * the value the parser computed, because neither service can recompute the original: the raw
     * extracted text is not part of the stored canonical artifact.
     */
    @Test
    @DisplayName("anchor derivation reproduces the parser's node offset")
    void derivedNodeOffsetsMatchParserOffsets() {
        var nodes = List.of(
                node("n1", NodeType.HEADING, 0, "4.2 Approval"),
                node("n2", NodeType.PARAGRAPH, 1,
                        "Changes must be approved before deployment. "
                                + "The approver must be independent of the implementing engineer."),
                node("n3", NodeType.PARAGRAPH, 2,
                        "Approval evidence is retained for seven years in the change record."));

        assertAnchorsResolve(nodes);
    }

    /**
     * A node whose FIRST sentence is dropped for being too short.
     *
     * <p>The regression this pins, found against real data. {@code SpanBuilder} skips any sentence
     * below its minimum length, so this node's first citable span begins seven characters in, at
     * {@code "The requirements apply..."}. Taking the earliest span's offset as the node's start
     * reports the node as beginning seven characters late and shifts every highlight in it by that
     * much — enough to cut a word, small enough to read as a rendering quirk.
     */
    @Test
    @DisplayName("a dropped leading sentence does not shift the node's start")
    void shortLeadingSentenceDoesNotShiftTheNodeStart() {
        var nodes = List.of(
                node("n1", NodeType.PARAGRAPH, 0,
                        "Scope. The requirements apply to application and infrastructure changes. "
                                + "They apply to employees, contractors, and managed-service personnel."),
                node("n2", NodeType.PARAGRAPH, 1,
                        "A control may be marked not applicable only when a rationale is recorded."));

        var offsets = charOffsets(nodes, documentText(nodes));
        var spans = spanBuilder.build("DOC-V1", nodes, n -> offsets.getOrDefault(n.nodeId(), 0));

        // The premise: "Scope." really is below the floor, so no span starts at the node's start.
        var firstNodeSpans = spans.stream().filter(s -> "n1".equals(s.parentNodeId())).toList();
        assertThat(firstNodeSpans).isNotEmpty();
        assertThat(firstNodeSpans.getFirst().charStart())
                .as("the earliest span begins AFTER the node does; that gap is the bug's cause")
                .isGreaterThan(offsets.get("n1"));

        assertAnchorsResolve(nodes);
    }

    /** Runs the two-step derivation the services perform and checks it against the parser. */
    private void assertAnchorsResolve(List<CanonicalNode> nodes) {
        var offsets = charOffsets(nodes, documentText(nodes));
        var spans = spanBuilder.build("DOC-V1", nodes, n -> offsets.getOrDefault(n.nodeId(), 0));

        Map<String, CanonicalNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.nodeId(), n));

        // Step 1, in EvidenceSpanController: the earliest span per node, as (absolute start, text).
        Map<String, EvidenceSpan> earliest = new HashMap<>();
        for (EvidenceSpan span : spans) {
            if (span.parentNodeId() == null || span.charStart() == null) {
                continue;
            }
            earliest.merge(span.parentNodeId(), span,
                    (a, b) -> a.charStart() <= b.charStart() ? a : b);
        }
        assertThat(earliest).isNotEmpty();

        // Step 2, in CanonicalViewService: solve for the node's start.
        earliest.forEach((nodeId, span) -> {
            String nodeText = byId.get(nodeId).text();
            int within = nodeText.indexOf(span.exactText().strip());
            assertThat(within).as("span text is present in the node it names").isGreaterThanOrEqualTo(0);

            assertThat(span.charStart() - within)
                    .as("derived start for %s equals the parser's", nodeId)
                    .isEqualTo(offsets.get(nodeId));
        });
    }
}
