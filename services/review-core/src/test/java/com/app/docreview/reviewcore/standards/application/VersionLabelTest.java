package com.app.docreview.reviewcore.standards.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version-label identity. Architecture §19.2.
 *
 * <p>The regression: the uniqueness check compared labels exactly, so uploading {@code "2"} while
 * {@code "2.0"} existed created a SECOND version of the same release. The administrator read the
 * list as unchanged, and any later reference to "version 2" was ambiguous between two documents.
 */
class VersionLabelTest {

    @Nested
    @DisplayName("labels that mean the same version")
    class SameVersion {

        @Test
        @DisplayName("trailing zero segments are equivalent")
        void trailingZeros() {
            assertThat(VersionLabel.sameVersion("2", "2.0")).isTrue();
            assertThat(VersionLabel.sameVersion("2.0", "2.0.0")).isTrue();
            assertThat(VersionLabel.sameVersion("2", "2.0.0")).isTrue();
        }

        @Test
        @DisplayName("a leading v is presentation, not identity")
        void leadingV() {
            assertThat(VersionLabel.sameVersion("v2.4", "2.4")).isTrue();
            assertThat(VersionLabel.sameVersion("V2", "2.0")).isTrue();
        }

        @Test
        @DisplayName("case and surrounding whitespace are ignored")
        void caseAndWhitespace() {
            assertThat(VersionLabel.sameVersion("  2.4  ", "2.4")).isTrue();
            assertThat(VersionLabel.sameVersion("DRAFT-A", "draft-a")).isTrue();
        }

        @Test
        @DisplayName("zero-padded segments are equivalent")
        void zeroPadded() {
            assertThat(VersionLabel.sameVersion("02.4", "2.4")).isTrue();
            assertThat(VersionLabel.sameVersion("2.04", "2.4")).isTrue();
        }
    }

    @Nested
    @DisplayName("labels that are genuinely different")
    class DifferentVersions {

        @Test
        @DisplayName("a non-zero segment is a different version")
        void differentSegments() {
            assertThat(VersionLabel.sameVersion("2.4", "2.5")).isFalse();
            assertThat(VersionLabel.sameVersion("2", "2.1")).isFalse();
            assertThat(VersionLabel.sameVersion("2.4", "2.4.1")).isFalse();
        }

        @Test
        @DisplayName("zero is a real version and does not collapse to empty")
        void zeroIsAVersion() {
            // "0" must not normalise away, or every 0.x version would collide with it.
            assertThat(VersionLabel.key("0")).isEqualTo("0");
            assertThat(VersionLabel.sameVersion("0", "0.0")).isTrue();
            assertThat(VersionLabel.sameVersion("0", "1")).isFalse();
            assertThat(VersionLabel.sameVersion("0.1", "0")).isFalse();
        }

        @Test
        @DisplayName("non-numeric labels are compared as written")
        void nonNumeric() {
            // Segment normalisation on these would be guesswork; two labels differing in any
            // character are more likely genuinely different than accidentally equal.
            assertThat(VersionLabel.sameVersion("2024-Q1", "2024-Q2")).isFalse();
            assertThat(VersionLabel.sameVersion("draft-a", "draft-b")).isFalse();
            assertThat(VersionLabel.sameVersion("2024-Q1", "2024-q1")).isTrue();
        }

        @Test
        @DisplayName("a numeric label and a non-numeric one are different")
        void mixedForms() {
            assertThat(VersionLabel.sameVersion("2.0", "2.0-draft")).isFalse();
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("null and blank normalise to the same empty key without throwing")
        void nullAndBlank() {
            assertThat(VersionLabel.key(null)).isEmpty();
            assertThat(VersionLabel.key("")).isEmpty();
            assertThat(VersionLabel.key("   ")).isEmpty();
        }

        @Test
        @DisplayName("a bare v is not treated as a numeric version")
        void bareV() {
            // Must not become the empty key and collide with a blank label.
            assertThat(VersionLabel.key("v")).isEqualTo("v");
        }

        @Test
        @DisplayName("a long segment chain does not overflow")
        void manySegments() {
            assertThat(VersionLabel.sameVersion("1.2.3.4.5", "1.2.3.4.5.0")).isTrue();
            assertThat(VersionLabel.sameVersion("1.2.3.4.5", "1.2.3.4.6")).isFalse();
        }
    }
}
