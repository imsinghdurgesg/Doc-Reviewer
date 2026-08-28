package com.app.docreview.documentprocessing.spans;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.CoordinateSpace;
import com.app.docreview.contracts.evidence.HighlightQuality;
import com.app.docreview.contracts.evidence.PageGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-span geometry and highlight quality. Architecture §11.3, §19.1.
 *
 * <p>The regression these tests exist for: a paragraph is split into one span per sentence, and every
 * sentence used to inherit the WHOLE NODE's rectangles. A finding about one clause then highlighted
 * the entire paragraph — an over-broad citation that looks precise, which is exactly the work the
 * tool was supposed to save the reviewer.
 */
class SpanBuilderGeometryTest {

    private final SpanBuilder builder = new SpanBuilder();

    /** Three sentences, so the node must be split and geometry must differ per sentence. */
    private static final String PARAGRAPH =
            "Releases require authorization by a release manager. "
            + "The approver must not have implemented the change. "
            + "Evidence is retained in the change record.";

    private CanonicalNode paragraph(List<BoundingBox> nodeBoxes) {
        return new CanonicalNode(
                "NODE-1", NodeType.PARAGRAPH, List.of("Approval"), 4, 2, 7,
                PARAGRAPH, TextHasher.hash(PARAGRAPH), nodeBoxes, false);
    }

    /** Geometry that returns one distinct rectangle per requested character range. */
    private static SpanGeometrySource rangeAddressable() {
        return new SpanGeometrySource() {
            @Override
            public List<BoundingBox> rectanglesFor(int charStart, int charEnd) {
                // x encodes the range, so a test can prove each span got ITS OWN range measured.
                return List.of(new BoundingBox(4, charStart, charEnd, 10, 12));
            }

            @Override
            public String renditionId() {
                return "renditions/PROC-V1/viewer.pdf";
            }

            @Override
            public String renditionSha256() {
                return "sha256:rendition";
            }

            @Override
            public PageGeometry pageGeometry(int pageNumber) {
                return new PageGeometry(pageNumber, 595, 842, 0);
            }
        };
    }

    @Nested
    @DisplayName("per-sentence geometry (the over-broad highlight regression)")
    class PerSentence {

        @Test
        @DisplayName("each sentence span gets rectangles for its OWN character range")
        void perSpanRanges() {
            // A single node box covering the whole paragraph — what used to be copied to every span.
            var nodeBox = new BoundingBox(4, 0, 0, 500, 40);
            var spans = builder.build("PROC-V1", List.of(paragraph(List.of(nodeBox))),
                    node -> 0, rangeAddressable());

            assertThat(spans).hasSize(3);
            // No two spans share geometry: the defect was every span carrying identical boxes.
            assertThat(spans.stream().map(s -> s.boundingBoxes().get(0)).distinct())
                    .as("each sentence must have its own rectangle")
                    .hasSize(3);
            // And the rectangles correspond to each span's own char range.
            for (var span : spans) {
                // BoundingBox coordinates are double; compare against the int offsets as doubles.
                assertThat(span.boundingBoxes().get(0).x())
                        .isEqualTo((double) span.charStart());
                assertThat(span.boundingBoxes().get(0).y())
                        .isEqualTo((double) span.charEnd());
            }
        }

        @Test
        @DisplayName("range-measured geometry is EXACT and bound to the rendition")
        void exactAndBound() {
            var spans = builder.build("PROC-V1", List.of(paragraph(List.of())),
                    node -> 0, rangeAddressable());

            assertThat(spans).allSatisfy(span -> {
                assertThat(span.highlightQuality()).isEqualTo(HighlightQuality.EXACT);
                assertThat(span.renditionSha256()).isEqualTo("sha256:rendition");
                assertThat(span.coordinateSpace()).isEqualTo(CoordinateSpace.PDF_POINTS_TOP_LEFT);
                assertThat(span.pageGeometry()).isNotNull();
            });
        }

        @Test
        @DisplayName("falling back to node geometry is reported APPROXIMATE, not EXACT")
        void nodeFallbackIsApproximate() {
            var nodeBox = new BoundingBox(4, 0, 0, 500, 40);
            // NONE supplies no ranges, so spans fall back to the node's boxes.
            var spans = builder.build("PROC-V1", List.of(paragraph(List.of(nodeBox))),
                    node -> 0, SpanGeometrySource.NONE);

            assertThat(spans).hasSize(3);
            assertThat(spans).allSatisfy(span -> {
                assertThat(span.boundingBoxes()).containsExactly(nodeBox);
                // The honest label: the box bounds the paragraph, not the sentence.
                assertThat(span.highlightQuality()).isEqualTo(HighlightQuality.APPROXIMATE);
                // Unbound geometry must not claim a rendition it cannot be verified against.
                assertThat(span.renditionSha256()).isNull();
            });
        }

        @Test
        @DisplayName("a single-sentence node's own boxes DO bound it exactly")
        void wholeNodeIsExact() {
            String single = "Releases require authorization by a release manager.";
            var box = new BoundingBox(4, 10, 20, 300, 12);
            var node = new CanonicalNode("NODE-2", NodeType.PARAGRAPH, List.of(), 4, 1, 3,
                    single, TextHasher.hash(single), List.of(box), false);

            var spans = builder.build("PROC-V1", List.of(node), n -> 0, SpanGeometrySource.NONE);

            assertThat(spans).hasSize(1);
            // Not APPROXIMATE: the span IS the node, so the node's box is its exact bound.
            assertThat(spans.get(0).highlightQuality()).isEqualTo(HighlightQuality.EXACT);
        }
    }

    @Nested
    @DisplayName("states when geometry is absent")
    class NoGeometry {

        @Test
        @DisplayName("a known page with no boxes is PAGE_ONLY, not UNRESOLVED")
        void pageOnly() {
            var node = new CanonicalNode("NODE-3", NodeType.PARAGRAPH, List.of(), 6, 1, 1,
                    PARAGRAPH, TextHasher.hash(PARAGRAPH), List.of(), false);

            var spans = builder.build("PROC-V1", List.of(node), n -> 0, SpanGeometrySource.NONE);

            assertThat(spans).isNotEmpty();
            // Navigable beats nothing: the reviewer can still be taken to the right page.
            assertThat(spans).allSatisfy(s ->
                    assertThat(s.highlightQuality()).isEqualTo(HighlightQuality.PAGE_ONLY));
            assertThat(spans).allSatisfy(s -> assertThat(s.page()).isEqualTo(6));
        }

        @Test
        @DisplayName("no page and no boxes is UNRESOLVED")
        void unresolved() {
            var node = new CanonicalNode("NODE-4", NodeType.PARAGRAPH, List.of(), null, null, 1,
                    PARAGRAPH, TextHasher.hash(PARAGRAPH), List.of(), false);

            var spans = builder.build("PROC-V1", List.of(node), n -> 0, SpanGeometrySource.NONE);

            assertThat(spans).isNotEmpty();
            assertThat(spans).allSatisfy(s ->
                    assertThat(s.highlightQuality()).isEqualTo(HighlightQuality.UNRESOLVED));
        }
    }

    @Nested
    @DisplayName("identity and robustness")
    class Identity {

        @Test
        @DisplayName("span IDs and text are unchanged by the geometry source")
        void identityIsIndependentOfGeometry() {
            var withRanges = builder.build("PROC-V1", List.of(paragraph(List.of())),
                    node -> 0, rangeAddressable());
            var without = builder.build("PROC-V1", List.of(paragraph(List.of())),
                    node -> 0, SpanGeometrySource.NONE);

            // Geometry is a display concern; changing it must not renumber citations (§11.3).
            assertThat(withRanges.stream().map(s -> s.spanId()).toList())
                    .isEqualTo(without.stream().map(s -> s.spanId()).toList());
            assertThat(withRanges.stream().map(s -> s.exactText()).toList())
                    .isEqualTo(without.stream().map(s -> s.exactText()).toList());
        }

        @Test
        @DisplayName("the legacy 3-arg build still works and matches the NONE source")
        void legacyOverloadUnchanged() {
            var nodes = List.of(paragraph(List.of(new BoundingBox(4, 0, 0, 500, 40))));

            assertThat(builder.build("PROC-V1", nodes, node -> 0))
                    .isEqualTo(builder.build("PROC-V1", nodes, node -> 0, SpanGeometrySource.NONE));
        }

        @Test
        @DisplayName("a null-text node is skipped rather than throwing")
        void nullTextIsSkipped() {
            // Reachable: charOffsets() in the job runner explicitly tolerates null node text, and an
            // atomic node would previously NPE on text().length() inside the builder.
            var nullText = new CanonicalNode("NODE-5", NodeType.HEADING, List.of(), 1, null, 0,
                    null, null, List.of(), false);
            var blank = new CanonicalNode("NODE-6", NodeType.TABLE_CELL, List.of(), 1, null, 1,
                    "   ", null, List.of(), false);

            assertThat(builder.build("PROC-V1", List.of(nullText, blank), n -> 0,
                    SpanGeometrySource.NONE)).isEmpty();
        }

        @Test
        @DisplayName("ordering stays monotonic across nodes and sub-spans")
        void orderingIsMonotonic() {
            var first = paragraph(List.of());
            String second = "A second paragraph with its own sentence. And another one here.";
            var next = new CanonicalNode("NODE-9", NodeType.PARAGRAPH, List.of(), 4, 3, 8,
                    second, TextHasher.hash(second), List.of(), false);

            var spans = builder.build("PROC-V1", List.of(first, next), n -> 0,
                    SpanGeometrySource.NONE);

            assertThat(spans.stream().map(s -> s.ordering()).toList())
                    .as("reading order must be strictly increasing")
                    .isSorted();
        }
    }
}
