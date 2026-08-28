package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.AtomicClauseResult;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import com.app.docreview.retrievalcomparison.comparison.aggregation.ClauseAggregator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clause-to-parent aggregation. Architecture §18.5.
 *
 * <p>Deterministic roll-up is what makes a parent verdict auditable, so every rule is pinned.
 */
class ClauseAggregatorTest {

    private final ClauseAggregator aggregator = new ClauseAggregator();

    private AtomicClauseResult clause(String id, ComparisonStatus status) {
        return new AtomicClauseResult(id, status, null, List.of(), "reason");
    }

    private SearchCoverage adequateCoverage() {
        return new SearchCoverage(List.of("4 Approval"), 120, 0.25, 0.31,
                List.of(), List.of(), true, true);
    }

    private SearchCoverage inadequateCoverage() {
        // Parsing incomplete: a scanned page could contain the requirement.
        return new SearchCoverage(List.of("4 Approval"), 120, 0.25, 0.31,
                List.of(), List.of(), false, true);
    }

    @Test
    @DisplayName("all clauses aligned -> parent ALIGNED")
    void allAligned() {
        assertThat(aggregator.aggregate(
                List.of(clause("A", ComparisonStatus.ALIGNED), clause("B", ComparisonStatus.ALIGNED)),
                adequateCoverage()))
                .isEqualTo(ComparisonStatus.ALIGNED);
    }

    @Nested
    @DisplayName("a contradiction outranks everything")
    class ContradictionPrecedence {

        @Test
        void oneContradictionBeatsManyAlignments() {
            // A process requiring approval four ways and permitting self-approval once is not
            // "mostly compliant" — the exception IS the finding, and averaging would bury it.
            assertThat(aggregator.aggregate(List.of(
                            clause("A", ComparisonStatus.ALIGNED),
                            clause("B", ComparisonStatus.ALIGNED),
                            clause("C", ComparisonStatus.ALIGNED),
                            clause("D", ComparisonStatus.CONTRADICTED)),
                    adequateCoverage()))
                    .isEqualTo(ComparisonStatus.CONTRADICTED);
        }

        @Test
        void contradictionBeatsInsufficientEvidence() {
            // §18.5: insufficient evidence yields "unless a stronger contradiction is present".
            assertThat(aggregator.aggregate(List.of(
                            clause("A", ComparisonStatus.INSUFFICIENT_EVIDENCE),
                            clause("B", ComparisonStatus.CONTRADICTED)),
                    adequateCoverage()))
                    .isEqualTo(ComparisonStatus.CONTRADICTED);
        }
    }

    @Test
    @DisplayName("some aligned and some missing -> PARTIAL")
    void mixedIsPartial() {
        assertThat(aggregator.aggregate(List.of(
                        clause("A", ComparisonStatus.ALIGNED),
                        clause("B", ComparisonStatus.MISSING)),
                adequateCoverage()))
                .isEqualTo(ComparisonStatus.PARTIAL);
    }

    @Nested
    @DisplayName("absence requires adequate coverage")
    class AbsenceRequiresCoverage {

        @Test
        void allMissingWithGoodCoverageIsMissing() {
            assertThat(aggregator.aggregate(
                    List.of(clause("A", ComparisonStatus.MISSING)), adequateCoverage()))
                    .isEqualTo(ComparisonStatus.MISSING);
        }

        @Test
        void allMissingWithBadCoverageIsInsufficient() {
            // The most damaging error this system can make is reporting a search failure as a
            // compliance gap. This is the check that prevents it.
            assertThat(aggregator.aggregate(
                    List.of(clause("A", ComparisonStatus.MISSING)), inadequateCoverage()))
                    .isEqualTo(ComparisonStatus.INSUFFICIENT_EVIDENCE);
        }
    }

    @Test
    @DisplayName("an unassessable clause is never absorbed into a confident verdict")
    void insufficientPropagates() {
        assertThat(aggregator.aggregate(List.of(
                        clause("A", ComparisonStatus.ALIGNED),
                        clause("B", ComparisonStatus.INSUFFICIENT_EVIDENCE)),
                adequateCoverage()))
                .isEqualTo(ComparisonStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("no clauses evaluated -> INSUFFICIENT_EVIDENCE, not MISSING")
    void noClausesIsInsufficient() {
        // Nothing was established either way, so asserting a gap would be asserting an untested claim.
        assertThat(aggregator.aggregate(List.of(), adequateCoverage()))
                .isEqualTo(ComparisonStatus.INSUFFICIENT_EVIDENCE);
    }
}
