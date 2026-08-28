package com.app.docreview.contracts.comparison;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coverage ledger and its completion invariant. Architecture §17.6, §18.4.
 *
 * <p><strong>What makes this necessary.</strong> A reader treats the absence of a finding as compliance.
 * That inference only holds if the requirement was actually tested. Three findings from thirty evaluated
 * requirements and three findings from eight evaluated out of thirty produce identical-looking reports,
 * and only the first says anything about the other twenty-seven.
 *
 * <p>The tests below pin the two properties that carry that weight: PENDING blocks completion, and an
 * empty ledger is not full coverage.
 */
class CoverageLedgerTest {

    private static CoverageLedger.Entry entry(String id, CoverageLedger.CoverageState state) {
        return new CoverageLedger.Entry("v-1", "rs-1", id, state, null, "Title of " + id);
    }

    @Nested
    @DisplayName("the completion invariant")
    class Completion {

        @Test
        @DisplayName("a PENDING requirement blocks completion")
        void pendingBlocksCompletion() {
            // THE invariant. A pending entry means a requirement was expected and never attempted --
            // reporting COMPLETED would present an untested control as a passing one.
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED),
                    entry("R2", CoverageLedger.CoverageState.PENDING)));

            assertThat(ledger.isComplete()).isFalse();
        }

        @Test
        @DisplayName("all EVALUATED is complete")
        void allEvaluatedIsComplete() {
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED),
                    entry("R2", CoverageLedger.CoverageState.EVALUATED)));

            assertThat(ledger.isComplete()).isTrue();
        }

        @Test
        @DisplayName("FAILED and SKIPPED are terminal, so they do not block completion")
        void terminalStatesDoNotBlock() {
            // Both are reported, and neither resolves by waiting. Blocking on them would leave a job
            // permanently non-terminal over a requirement that will never be evaluated -- while still
            // counting toward untested, which is what actually protects the reader.
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED),
                    entry("R2", CoverageLedger.CoverageState.FAILED),
                    entry("R3", CoverageLedger.CoverageState.SKIPPED)));

            assertThat(ledger.isComplete()).isTrue();
            assertThat(ledger.untestedCount())
                    .as("terminal but untested still must not read as compliance")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("untested counts everything that is not EVALUATED")
        void untestedCountsNonEvaluated() {
            // The number a report must surface. Only EVALUATED produced a verdict; every other state
            // leaves a requirement whose absence from the findings is meaningless.
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED),
                    entry("R2", CoverageLedger.CoverageState.PENDING),
                    entry("R3", CoverageLedger.CoverageState.FAILED),
                    entry("R4", CoverageLedger.CoverageState.SKIPPED)));

            assertThat(ledger.evaluatedCount()).isEqualTo(1);
            assertThat(ledger.untestedCount()).isEqualTo(3);
            assertThat(ledger.expectedCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("an EMPTY ledger is 0.0 coverage, NOT 1.0")
        void emptyLedgerIsNotFullCoverage() {
            // "Nothing was expected" and "everything was covered" are different claims. Treating the
            // first as full coverage would report a comparison that selected no standards -- and
            // therefore tested nothing at all -- as a complete, clean review.
            var empty = new CoverageLedger("CMP-1", List.of());

            assertThat(empty.coverageRatio()).isZero();
            assertThat(empty.expectedCount()).isZero();
        }

        @Test
        @DisplayName("coverage ratio is evaluated over expected")
        void coverageRatio() {
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED),
                    entry("R2", CoverageLedger.CoverageState.EVALUATED),
                    entry("R3", CoverageLedger.CoverageState.EVALUATED),
                    entry("R4", CoverageLedger.CoverageState.PENDING)));

            assertThat(ledger.coverageRatio()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("byState omits states with no entries rather than reporting zero")
        void byStateOmitsAbsent() {
            var ledger = new CoverageLedger("CMP-1", List.of(
                    entry("R1", CoverageLedger.CoverageState.EVALUATED)));

            assertThat(ledger.byState())
                    .containsEntry(CoverageLedger.CoverageState.EVALUATED, 1L)
                    .doesNotContainKey(CoverageLedger.CoverageState.PENDING);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("a null entry list becomes empty, not a crash")
        void nullEntriesAreEmpty() {
            var ledger = new CoverageLedger("CMP-1", null);

            assertThat(ledger.entries()).isEmpty();
            assertThat(ledger.isComplete())
                    .as("no entries means nothing pending, so vacuously complete")
                    .isTrue();
            assertThat(ledger.coverageRatio())
                    .as("but NOT full coverage -- see emptyLedgerIsNotFullCoverage")
                    .isZero();
        }

        @Test
        @DisplayName("entries are defensively copied")
        void entriesAreImmutable() {
            // The ledger is reported and may be retained; a caller mutating its list afterwards would
            // change what a completed comparison claims was covered (§19.2).
            var mutable = new java.util.ArrayList<CoverageLedger.Entry>();
            mutable.add(entry("R1", CoverageLedger.CoverageState.EVALUATED));
            var ledger = new CoverageLedger("CMP-1", mutable);

            mutable.add(entry("R2", CoverageLedger.CoverageState.PENDING));

            assertThat(ledger.entries()).hasSize(1);
            assertThat(ledger.isComplete()).isTrue();
        }
    }
}
