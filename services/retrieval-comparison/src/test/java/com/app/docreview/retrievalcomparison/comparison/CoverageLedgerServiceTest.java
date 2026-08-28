package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.comparison.application.CoverageLedgerService;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Building the coverage ledger from the requirement inventory. Architecture §17.6, §18.4.
 *
 * <p><strong>The property that matters.</strong> The ledger is seeded from the EXPECTED material
 * requirements, then marked off against the findings. Deriving it the other way round — counting
 * findings — cannot detect the failure it exists for, because a requirement that was never evaluated
 * leaves no trace in a list of findings. {@link Inventory#requirementWithNoFindingIsPending} is the test
 * that would fail if someone rewrote this to count findings.
 */
class CoverageLedgerServiceTest {

    private final CoverageLedgerService service = new CoverageLedgerService();

    private static StandardRequirementDocument requirement(String id, NormativeStrength strength) {
        var extracted = new ExtractedRequirement(
                id, null, null, "Title", List.of("span-1"), strength,
                "Changes must be approved.", List.of(), null, List.of("record"), 0.9);
        return new StandardRequirementDocument("std-1", "v-1", "rs-1", extracted, null, null, null);
    }

    private static Finding finding(String requirementId) {
        // gapType and severity are null: ALIGNED findings have no gap, and severity comes from standard
        // metadata rather than the model (§17.7). Neither is what this test is about.
        return new Finding("f-" + requirementId, "CMP-1", "v-1", "rs-1", requirementId,
                null, "Title", ComparisonStatus.ALIGNED, null, null,
                List.of("s-1"), List.of("p-1"), List.<String>of(),
                List.<com.app.docreview.contracts.comparison.AtomicClauseResult>of(),
                "reason", null, 0.9, false,
                // Applicability is not what this test is about; APPLICABLE is the default and the
                // state every compared requirement is in.
                com.app.docreview.contracts.enums.ApplicabilityStatus.APPLICABLE, null, null, null);
    }

    @Nested
    @DisplayName("inventory-driven, not finding-driven")
    class Inventory {

        @Test
        @DisplayName("a requirement with no finding is PENDING, not absent")
        void requirementWithNoFindingIsPending() {
            // THE test. Two requirements expected, one finding produced. A finding-derived ledger would
            // report one entry and claim complete coverage; the untested requirement would vanish and
            // its absence from the findings would read as compliance.
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY),
                            requirement("R2", NormativeStrength.MANDATORY)),
                    List.of(finding("R1")),
                    Set.of(), Map.of());

            assertThat(ledger.expectedCount()).isEqualTo(2);
            assertThat(ledger.evaluatedCount()).isEqualTo(1);
            assertThat(ledger.untestedCount()).isEqualTo(1);
            assertThat(ledger.isComplete())
                    .as("a silently dropped requirement must block completion")
                    .isFalse();
        }

        @Test
        @DisplayName("every requirement evaluated yields a complete ledger")
        void allEvaluated() {
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY),
                            requirement("R2", NormativeStrength.MANDATORY)),
                    List.of(finding("R1"), finding("R2")),
                    Set.of(), Map.of());

            assertThat(ledger.isComplete()).isTrue();
            assertThat(ledger.coverageRatio()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("only material requirements are expected")
    class Materiality {

        @Test
        @DisplayName("advisory requirements are excluded from the ledger entirely")
        void advisoryExcluded() {
            // An advisory requirement is never compared (§12.2). Listing it as untested would report a
            // permanent gap that no comparison could ever close, and would drag coverage below 100%
            // forever.
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY),
                            requirement("R2", NormativeStrength.ADVISORY)),
                    List.of(finding("R1")),
                    Set.of(), Map.of());

            assertThat(ledger.expectedCount())
                    .as("only the mandatory requirement is expected")
                    .isEqualTo(1);
            assertThat(ledger.isComplete()).isTrue();
        }

        @Test
        @DisplayName("prohibitions and conditionals ARE material")
        void prohibitionsAreMaterial() {
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.PROHIBITION),
                            requirement("R2", NormativeStrength.CONDITIONAL)),
                    List.of(), Set.of(), Map.of());

            assertThat(ledger.expectedCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("failure and skip attribution")
    class Attribution {

        @Test
        @DisplayName("a failed requirement is FAILED, not PENDING")
        void failedIsDistinctFromPending() {
            // The distinction is actionable: FAILED will not resolve by waiting and names a control to
            // check by hand, while PENDING means something dropped it before evaluation was attempted.
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY)),
                    List.of(), Set.of("R1"), Map.of());

            assertThat(ledger.byState())
                    .containsEntry(CoverageLedger.CoverageState.FAILED, 1L);
            assertThat(ledger.isComplete())
                    .as("FAILED is terminal")
                    .isTrue();
            assertThat(ledger.untestedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a finding wins over a recorded failure")
        void findingBeatsFailure() {
            // If a requirement both produced a finding and reported an error, the verdict exists, so
            // its absence cannot be misread. Checking failure first would discard a real finding.
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY)),
                    List.of(finding("R1")), Set.of("R1"), Map.of());

            assertThat(ledger.byState())
                    .containsEntry(CoverageLedger.CoverageState.EVALUATED, 1L);
        }

        @Test
        @DisplayName("a skipped requirement records its reason")
        void skippedCarriesReason() {
            // A silently skipped requirement is indistinguishable from a passing one, so the reason is
            // the whole point of the state.
            var ledger = service.build("CMP-1",
                    List.of(requirement("R1", NormativeStrength.MANDATORY)),
                    List.of(), Set.of(), Map.of("R1", "Ruled inapplicable"));

            assertThat(ledger.entries()).singleElement()
                    .satisfies(e -> {
                        assertThat(e.state()).isEqualTo(CoverageLedger.CoverageState.SKIPPED);
                        assertThat(e.detail()).isEqualTo("Ruled inapplicable");
                    });
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("no requirements yields an empty ledger, not full coverage")
        void noRequirements() {
            // A comparison that selected no standards tested nothing. Reporting 100% coverage would be
            // the most misleading possible output.
            var ledger = service.build("CMP-1", List.of(), List.of(), Set.of(), Map.of());

            assertThat(ledger.expectedCount()).isZero();
            assertThat(ledger.coverageRatio()).isZero();
        }

        @Test
        @DisplayName("nulls are tolerated")
        void nullsTolerated() {
            var ledger = service.build("CMP-1", null, null, null, null);

            assertThat(ledger.entries()).isEmpty();
        }
    }
}
