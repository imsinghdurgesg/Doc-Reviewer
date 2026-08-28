package com.app.docreview.reviewcore.standards;

import com.app.docreview.reviewcore.standards.application.CuratedMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Curated metadata survives reprocessing. Architecture §15.1, §8.1.
 *
 * <p>MongoDB is the source of truth for department, standardFamily, and documentType; Qdrant payloads
 * are a projection rebuilt from it. The failure this guards is subtle and permanent: a reprocessing run
 * writes {@code "Unknown"} over a curated label, and from then on every "is it already set?" check sees
 * a non-blank value and treats the placeholder as an administrator's choice. The real value can never
 * be restored by reprocessing, only by hand — and nobody knows to look.
 */
class CuratedMetadataTest {

    @Nested
    @DisplayName("placeholders are absence, not values")
    class Placeholders {

        @Test
        @DisplayName("UNKNOWN and friends are not meaningful")
        void placeholdersAreNotMeaningful() {
            // Models emit these readily when asked to classify something they cannot determine. The
            // closed-vocabulary prompt permits abstention, so any of these reaching a write path is a
            // bug being caught rather than expected input.
            for (String placeholder : new String[]{
                    "UNKNOWN", "unknown", " Unknown ", "N/A", "n/a", "NA", "None", "null",
                    "TBD", "not applicable", "Not Specified", "undetermined", "-", "--"}) {
                assertThat(CuratedMetadata.isMeaningful(placeholder))
                        .as("%s must be treated as absence", placeholder)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("null and blank are not meaningful")
        void blanksAreNotMeaningful() {
            assertThat(CuratedMetadata.isMeaningful(null)).isFalse();
            assertThat(CuratedMetadata.isMeaningful("")).isFalse();
            assertThat(CuratedMetadata.isMeaningful("   ")).isFalse();
        }

        @Test
        @DisplayName("a real label is meaningful")
        void realValuesAreMeaningful() {
            assertThat(CuratedMetadata.isMeaningful("Procurement")).isTrue();
            assertThat(CuratedMetadata.isMeaningful("Information Security")).isTrue();
            // Not a placeholder despite containing one as a substring — the check is on the whole
            // trimmed value, so a real department is never mistaken for absence.
            assertThat(CuratedMetadata.isMeaningful("Unknown Risks Working Group")).isTrue();
        }
    }

    @Nested
    @DisplayName("preserve: curation wins")
    class Preserve {

        @Test
        @DisplayName("a curated value is never replaced by an inferred one")
        void curatedBeatsInferred() {
            // The human read the document; the model read its first few pages.
            assertThat(CuratedMetadata.preserve("Procurement", "Finance"))
                    .isEqualTo("Procurement");
        }

        @Test
        @DisplayName("a curated value is never replaced by null or a placeholder")
        void curatedBeatsAbsence() {
            assertThat(CuratedMetadata.preserve("Procurement", null)).isEqualTo("Procurement");
            assertThat(CuratedMetadata.preserve("Procurement", "")).isEqualTo("Procurement");
            assertThat(CuratedMetadata.preserve("Procurement", "UNKNOWN")).isEqualTo("Procurement");
        }

        @Test
        @DisplayName("an inferred value fills a genuinely empty field")
        void inferredFillsTheGap() {
            // Inference is useful exactly here: nothing curated exists, so a reasonable guess beats a
            // blank ranking signal.
            assertThat(CuratedMetadata.preserve(null, "Finance")).isEqualTo("Finance");
            assertThat(CuratedMetadata.preserve("  ", "Finance")).isEqualTo("Finance");
            // Trimmed on the way in, so a stray space cannot create two variants of one label.
            assertThat(CuratedMetadata.preserve(null, "  Finance  ")).isEqualTo("Finance");
        }

        @Test
        @DisplayName("a placeholder is never STORED, even over an empty field")
        void placeholderNeverLands() {
            // THE defect. Storing "UNKNOWN" would make the field look curated forever afterwards and
            // permanently block the real value from being written by any later run.
            assertThat(CuratedMetadata.preserve(null, "UNKNOWN")).isNull();
            assertThat(CuratedMetadata.preserve(null, "N/A")).isNull();
            assertThat(CuratedMetadata.preserve("", "TBD")).isEmpty();
        }
    }

    @Nested
    @DisplayName("wouldChange: reprocessing is a no-op over curated data")
    class WouldChange {

        @Test
        @DisplayName("reprocessing already-curated metadata changes nothing")
        void curatedIsIdempotent() {
            // Lets a caller skip the database write, and lets this test assert the property that
            // matters: running indexing twice must not touch curated fields.
            assertThat(CuratedMetadata.wouldChange("Procurement", "Finance")).isFalse();
            assertThat(CuratedMetadata.wouldChange("Procurement", null)).isFalse();
            assertThat(CuratedMetadata.wouldChange("Procurement", "UNKNOWN")).isFalse();
        }

        @Test
        @DisplayName("filling an empty field is a change")
        void fillingIsAChange() {
            assertThat(CuratedMetadata.wouldChange(null, "Finance")).isTrue();
        }

        @Test
        @DisplayName("a placeholder over an empty field is NOT a change")
        void placeholderIsNotAChange() {
            assertThat(CuratedMetadata.wouldChange(null, "UNKNOWN")).isFalse();
        }
    }
}
