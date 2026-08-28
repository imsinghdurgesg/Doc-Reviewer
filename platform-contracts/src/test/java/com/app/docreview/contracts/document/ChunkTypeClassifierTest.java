package com.app.docreview.contracts.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eight-type chunk taxonomy. Architecture §11.4, §12.8, §15.2.
 *
 * <p><strong>What this protects.</strong> Retrieval previously knew only BODY vs CONTROL_SECTION, so a
 * definition, a scope statement and an obligation were equally eligible to be returned as evidence that
 * a requirement was satisfied. The dangerous direction is over-typing: labelling a definition or a
 * guidance note as REQUIREMENT invents an obligation the standard never imposed, and the resulting
 * finding looks fully substantiated.
 *
 * <p>So the tests below are weighted toward what must NOT be typed REQUIREMENT, and toward the
 * conservative default when nothing matches.
 */
class ChunkTypeClassifierTest {

    @Nested
    @DisplayName("headings are the strongest signal")
    class Headings {

        @Test
        @DisplayName("a definitions section is DEFINITION even when it contains 'must'")
        void definitionsBeatIncidentalMust() {
            // §12.8 rule 9: definitions are never promoted to controls. Definition text routinely
            // contains modal verbs -- "Privileged Access means access that must bypass controls" --
            // and reading that as an obligation fabricates a requirement to bypass controls.
            var type = ChunkTypeClassifier.classify(
                    List.of("3. Definitions"),
                    "Privileged Access means access that must bypass standard authorisation.");

            assertThat(type).isEqualTo(ChunkType.DEFINITION);
        }

        @Test
        @DisplayName("an exceptions section is EXCEPTION, not REQUIREMENT")
        void exceptionsSection() {
            // An exception found and typed as a requirement inverts its meaning: text that REMOVES an
            // obligation would be reported as imposing one.
            var type = ChunkTypeClassifier.classify(
                    List.of("Appendix B: Exceptions"),
                    "Emergency changes must be recorded within 24 hours instead.");

            assertThat(type).isEqualTo(ChunkType.EXCEPTION);
        }

        @Test
        @DisplayName("scope and purpose are SCOPE")
        void scopeSection() {
            var type = ChunkTypeClassifier.classify(
                    List.of("1. Purpose and Scope"),
                    "This standard governs technology change management across all environments.");

            assertThat(type).isEqualTo(ChunkType.SCOPE);
        }

        @Test
        @DisplayName("applicability is distinguished from scope")
        void applicabilitySection() {
            // Phase 2's applicability decision depends on these being separable: scope says what
            // subject matter is covered, applicability says whether this document falls inside it.
            var type = ChunkTypeClassifier.classify(
                    List.of("2. Applicability"),
                    "This standard applies to all third parties processing university data.");

            assertThat(type).isEqualTo(ChunkType.APPLICABILITY);
        }

        @Test
        @DisplayName("document control metadata is DOCUMENT_PROFILE")
        void documentControlSection() {
            var type = ChunkTypeClassifier.classify(
                    List.of("Document Control"),
                    "Owner: CISO. Version 2.4. Approved 2026-01-15.");

            assertThat(type).isEqualTo(ChunkType.DOCUMENT_PROFILE);
        }

        @Test
        @DisplayName("a requirements heading yields REQUIREMENT")
        void requirementsSection() {
            var type = ChunkTypeClassifier.classify(
                    List.of("4. Control Requirements"),
                    "All changes shall be approved by the Change Advisory Board.");

            assertThat(type).isEqualTo(ChunkType.REQUIREMENT);
        }
    }

    @Nested
    @DisplayName("a real obligation is not lost to a soft heading")
    class ObligationWins {

        @Test
        @DisplayName("an appendix containing a clear obligation is REQUIREMENT")
        void appendixWithObligation() {
            // Standards do put real controls in appendices. Typing this GUIDANCE would rank a genuine
            // requirement below advisory prose, and it might never be retrieved for comparison.
            var type = ChunkTypeClassifier.classify(
                    List.of("Appendix A"),
                    "Production systems must be patched within 30 days of a critical advisory.");

            assertThat(type).isEqualTo(ChunkType.REQUIREMENT);
        }

        @Test
        @DisplayName("a guidance heading with only advisory text stays GUIDANCE")
        void guidanceStaysGuidance() {
            var type = ChunkTypeClassifier.classify(
                    List.of("Guidance"),
                    "Teams should consider staging changes during low-traffic windows.");

            assertThat(type).isEqualTo(ChunkType.GUIDANCE);
        }
    }

    @Nested
    @DisplayName("body cues when the heading says nothing")
    class BodyCues {

        @Test
        @DisplayName("definition phrasing without an obligation is DEFINITION")
        void definitionByPhrasing() {
            var type = ChunkTypeClassifier.classify(
                    List.of(),
                    "\"Change Window\" refers to the approved period during which changes occur.");

            assertThat(type).isEqualTo(ChunkType.DEFINITION);
        }

        @Test
        @DisplayName("an obligation with no heading is REQUIREMENT")
        void obligationByPhrasing() {
            var type = ChunkTypeClassifier.classify(
                    null,
                    "Each deployment shall be accompanied by a documented rollback plan.");

            assertThat(type).isEqualTo(ChunkType.REQUIREMENT);
        }

        @Test
        @DisplayName("retention phrasing is EVIDENCE_EXPECTATION")
        void evidenceByPhrasing() {
            var type = ChunkTypeClassifier.classify(
                    List.of(),
                    "Approval records shall be retained for seven years and available for audit.");

            assertThat(type).isEqualTo(ChunkType.EVIDENCE_EXPECTATION);
        }
    }

    @Nested
    @DisplayName("the conservative default")
    class Defaults {

        @Test
        @DisplayName("unclassifiable text is GUIDANCE, never REQUIREMENT")
        void unknownIsGuidance() {
            // THE safety property. An unclassified chunk must not be presented as carrying an
            // obligation: a wrong GUIDANCE costs ranking quality, a wrong REQUIREMENT fabricates a
            // duty and every finding built on it is unfounded.
            assertThat(ChunkTypeClassifier.classify(List.of(), "The team met on Tuesday."))
                    .isEqualTo(ChunkType.GUIDANCE);
            assertThat(ChunkTypeClassifier.classify(null, null)).isEqualTo(ChunkType.GUIDANCE);
            assertThat(ChunkTypeClassifier.classify(List.of(), "")).isEqualTo(ChunkType.GUIDANCE);
        }

        @Test
        @DisplayName("never returns null")
        void neverNull() {
            // Callers write this straight into a vector payload; a null would become the string "null"
            // and match no type on read.
            assertThat(ChunkTypeClassifier.classify(null, null)).isNotNull();
            assertThat(ChunkTypeClassifier.classify(List.of(""), "   ")).isNotNull();
        }
    }

    @Nested
    @DisplayName("the enum contract")
    class EnumContract {

        @Test
        @DisplayName("the eight specified types exist, and the classifier only ever emits those")
        void eightSpecifiedTypes() {
            // The taxonomy is specified as exactly these eight. UNCLASSIFIED_LEGACY is a NINTH enum
            // constant that is deliberately not part of the taxonomy: it records the absence of a
            // determination on pre-taxonomy chunks and is never produced by classify().
            assertThat(java.util.Arrays.stream(ChunkType.values())
                    .filter(ChunkType::isClassified).toList())
                    .containsExactly(
                            ChunkType.DOCUMENT_PROFILE, ChunkType.SCOPE, ChunkType.APPLICABILITY,
                            ChunkType.REQUIREMENT, ChunkType.DEFINITION, ChunkType.GUIDANCE,
                            ChunkType.EXCEPTION, ChunkType.EVIDENCE_EXPECTATION);
        }

        @Test
        @DisplayName("classify() never returns UNCLASSIFIED_LEGACY")
        void classifyNeverEmitsLegacy() {
            // The legacy type is a READ-BACK value only. Emitting it from classification would mean
            // new content was indexed with no determination, which is the state this taxonomy exists
            // to eliminate.
            var samples = List.of(
                    "Changes must be approved.", "Privileged Access means elevated rights.",
                    "The team met on Tuesday.", "", "Records shall be retained for seven years.");
            for (String sample : samples) {
                assertThat(ChunkTypeClassifier.classify(List.of(), sample))
                        .isNotEqualTo(ChunkType.UNCLASSIFIED_LEGACY);
                assertThat(ChunkTypeClassifier.classify(List.of("Requirements"), sample))
                        .isNotEqualTo(ChunkType.UNCLASSIFIED_LEGACY);
            }
        }

        @Test
        @DisplayName("only REQUIREMENT and EXCEPTION are normative")
        void normativeTypes() {
            // An exception counts because it changes an obligation's reach, and therefore whether a
            // finding is a gap. Nothing else can create or remove a duty.
            assertThat(java.util.Arrays.stream(ChunkType.values())
                    .filter(ChunkType::isNormative).toList())
                    .containsExactly(ChunkType.REQUIREMENT, ChunkType.EXCEPTION);
        }

        @Test
        @DisplayName("legacy types are PRESERVED as unclassified, not reinterpreted as GUIDANCE")
        void legacyValuesArePreserved() {
            // Mapping BODY/CONTROL_SECTION to GUIDANCE would assert that 212 already-indexed chunks
            // are non-normative — something the V1 index never recorded. It distinguished chunks only
            // by "does this have a heading", so a 199-chunk CONTROL_SECTION population almost
            // certainly contains real requirements.
            //
            // UNCLASSIFIED_LEGACY says what is actually true: the type was never determined, and these
            // documents need reprocessing rather than relabelling.
            assertThat(ChunkType.parse("BODY")).isEqualTo(ChunkType.UNCLASSIFIED_LEGACY);
            assertThat(ChunkType.parse("CONTROL_SECTION")).isEqualTo(ChunkType.UNCLASSIFIED_LEGACY);
            assertThat(ChunkType.parse("control_section")).isEqualTo(ChunkType.UNCLASSIFIED_LEGACY);
        }

        @Test
        @DisplayName("an unclassified legacy chunk is not reported as classified")
        void legacyIsNotClassified() {
            // Ranking must treat these as "unknown normative status", not as a determination.
            assertThat(ChunkType.UNCLASSIFIED_LEGACY.isClassified()).isFalse();
            assertThat(ChunkType.REQUIREMENT.isClassified()).isTrue();
            assertThat(ChunkType.GUIDANCE.isClassified()).isTrue();
        }

        @Test
        @DisplayName("legacy chunks are NOT normative, but that is separate from being classified")
        void legacyIsNotNormative() {
            // Not normative: nothing established that they carry obligations, so they must not be
            // cited as though they did. Also not GUIDANCE: nothing established they are advisory.
            assertThat(ChunkType.UNCLASSIFIED_LEGACY.isNormative()).isFalse();
            assertThat(ChunkType.UNCLASSIFIED_LEGACY).isNotEqualTo(ChunkType.GUIDANCE);
        }

        @Test
        @DisplayName("unparseable values fall back to GUIDANCE")
        void unparseableIsGuidance() {
            // Distinct from the legacy case: an unrecognised string is not a known V1 value, so the
            // conservative non-normative default applies.
            assertThat(ChunkType.parse("nonsense")).isEqualTo(ChunkType.GUIDANCE);
            assertThat(ChunkType.parse(null)).isEqualTo(ChunkType.GUIDANCE);
            assertThat(ChunkType.parse("  ")).isEqualTo(ChunkType.GUIDANCE);
        }

        @Test
        @DisplayName("a known value round-trips case-insensitively")
        void parseIsLenient() {
            assertThat(ChunkType.parse("requirement")).isEqualTo(ChunkType.REQUIREMENT);
            assertThat(ChunkType.parse("  Scope  ")).isEqualTo(ChunkType.SCOPE);
        }
    }
}
