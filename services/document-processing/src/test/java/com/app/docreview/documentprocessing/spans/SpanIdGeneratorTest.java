package com.app.docreview.documentprocessing.spans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Span identity. Architecture §11.3.
 *
 * <p>Span IDs must be derived, not random: a re-parse of identical content has to produce identical
 * IDs or every previously stored finding cites spans that no longer exist.
 */
class SpanIdGeneratorTest {

    @Test
    @DisplayName("the same version and position always yield the same span id")
    void deterministic() {
        assertThat(SpanIdGenerator.spanId("STD-V2.4", 21, 0))
                .isEqualTo(SpanIdGenerator.spanId("STD-V2.4", 21, 0));
    }

    @Test
    @DisplayName("different document versions cannot collide")
    void versionScoped() {
        // This is what makes cross-version citation impossible by construction (§16.5): a span ID
        // from one version cannot accidentally validate against another.
        assertThat(SpanIdGenerator.spanId("STD-V2.4", 21, 0))
                .isNotEqualTo(SpanIdGenerator.spanId("STD-V2.5", 21, 0));
    }

    @Test
    @DisplayName("sub-spans of one node are distinct")
    void subIndexDistinguishes() {
        assertThat(SpanIdGenerator.spanId("V", 5, 0)).isNotEqualTo(SpanIdGenerator.spanId("V", 5, 1));
    }

    @Test
    @DisplayName("ids are human-readable for diagnosis")
    void readablePrefixes() {
        // An opaque UUID makes tracing a bad citation through logs considerably harder.
        assertThat(SpanIdGenerator.spanId("V", 1, 0)).startsWith("SPAN-");
        assertThat(SpanIdGenerator.nodeId("V", 1)).startsWith("NODE-");
        assertThat(SpanIdGenerator.chunkId("V", 1)).startsWith("CHUNK-");
    }
}
