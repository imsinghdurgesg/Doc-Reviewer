package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ComparisonStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applicability and comparison stay SEPARATE. Architecture §12.5, §17.5, §17.6.
 *
 * <p><strong>What merging them would cost.</strong> They answer different questions — <em>should this be
 * measured?</em> versus <em>having measured it, does it comply?</em> Forcing "does not apply" onto the
 * compliance scale has no correct answer:
 *
 * <ul>
 *   <li>{@code MISSING} reports a gap the standard never demanded;</li>
 *   <li>{@code ALIGNED} claims compliance that was never assessed;</li>
 *   <li>{@code INSUFFICIENT_EVIDENCE} blames the document's evidence when none was needed.</li>
 * </ul>
 *
 * <p>These tests pin the separation structurally, so a later change that adds {@code NOT_APPLICABLE} to
 * {@code ComparisonStatus} — the obvious-looking simplification — fails rather than silently landing.
 */
class ApplicabilitySeparationTest {

    @Nested
    @DisplayName("the two enums do not overlap")
    class NoOverlap {

        @Test
        @DisplayName("ComparisonStatus contains no applicability value")
        void comparisonStatusHasNoApplicabilityValue() {
            // The specific regression this prevents: someone adds NOT_APPLICABLE to ComparisonStatus
            // because it looks like one more verdict, and every reader thereafter cannot distinguish
            // "out of scope" from "assessed and compliant".
            var comparisonNames = Arrays.stream(ComparisonStatus.values()).map(Enum::name).toList();

            assertThat(comparisonNames)
                    .as("applicability is not a compliance verdict")
                    .doesNotContain("NOT_APPLICABLE", "APPLICABLE", "UNCERTAIN");
        }

        @Test
        @DisplayName("ApplicabilityStatus contains no compliance verdict")
        void applicabilityStatusHasNoComplianceValue() {
            var applicabilityNames =
                    Arrays.stream(ApplicabilityStatus.values()).map(Enum::name).toList();

            assertThat(applicabilityNames)
                    .as("scope is not a compliance verdict either — the separation cuts both ways")
                    .doesNotContain("ALIGNED", "PARTIAL", "MISSING", "CONTRADICTED",
                            "INSUFFICIENT_EVIDENCE");
        }

        @Test
        @DisplayName("UNCERTAIN is distinct from INSUFFICIENT_EVIDENCE")
        void unclearIsNotInsufficientEvidence() {
            // Both express uncertainty, about different things. INSUFFICIENT_EVIDENCE says the
            // DOCUMENT's evidence was too weak for a requirement that definitely applies;
            // UNCERTAIN says we could not tell whether the requirement applies at all.
            // Conflating them would report a scope question as a document deficiency.
            assertThat(ApplicabilityStatus.UNCERTAIN.name())
                    .isNotEqualTo(ComparisonStatus.INSUFFICIENT_EVIDENCE.name());
        }
    }

    @Nested
    @DisplayName("a finding carries both, independently")
    class FindingCarriesBoth {

        private Finding finding(ComparisonStatus status, ApplicabilityStatus applicability) {
            return new Finding("f-1", "CMP-1", "v-1", "rs-1", "R1", null, "Title",
                    status, null, null,
                    List.of("s-1"), List.of("p-1"), List.of(), List.of(),
                    "reason", null, 0.9, false, applicability, null, null, null,
                null, List.of(), true);
        }

        @Test
        @DisplayName("a NOT_APPLICABLE requirement is never actionable, whatever its status")
        void notApplicableIsNeverActionable() {
            // It was not measured, so there is nothing to act on. Without this, an excluded requirement
            // whose status field happens to hold MISSING would appear in the reviewer's work queue as a
            // gap the standard never demanded.
            for (var status : ComparisonStatus.values()) {
                assertThat(finding(status, ApplicabilityStatus.NOT_APPLICABLE).actionable())
                        .as("NOT_APPLICABLE + %s must not be actionable", status)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("an applicable non-aligned finding IS actionable")
        void applicableGapIsActionable() {
            assertThat(finding(ComparisonStatus.MISSING, ApplicabilityStatus.APPLICABLE).actionable())
                    .isTrue();
            assertThat(finding(ComparisonStatus.ALIGNED, ApplicabilityStatus.APPLICABLE).actionable())
                    .as("aligned is not a gap")
                    .isFalse();
        }

        @Test
        @DisplayName("an UNCLEAR-scope gap is still actionable")
        void unclearScopeGapIsActionable() {
            // Doubt about scope does not withhold the finding. A reviewer needs to see both the gap and
            // the scope question, and deciding for them would hide one of the two.
            assertThat(finding(ComparisonStatus.MISSING,
                    ApplicabilityStatus.UNCERTAIN).actionable())
                    .isTrue();
        }

        @Test
        @DisplayName("a null applicability reads as APPLICABLE")
        void nullApplicabilityDefaultsToApplicable() {
            // Findings recorded before applicability existed have no value. A finding that exists at all
            // was produced by comparing the requirement, which only happens when it applied — so
            // APPLICABLE is both the safe default and the historically accurate one.
            var legacy = finding(ComparisonStatus.MISSING, null);

            assertThat(legacy.applicability()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(legacy.actionable())
                    .as("a historical gap must not stop being actionable")
                    .isTrue();
        }
    }
}
