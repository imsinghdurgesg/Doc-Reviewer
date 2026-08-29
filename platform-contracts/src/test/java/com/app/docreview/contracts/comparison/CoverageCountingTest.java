package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.comparison.CoverageLedger.CoverageState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage arithmetic. Architecture §17.6, §18.4.
 *
 * <p><strong>Why this is worth its own test.</strong> These counts are the platform's claim about how
 * much of a standard it actually measured. Every failure mode here is silent and reassuring: a
 * requirement counted as evaluated when it produced no verdict inflates coverage; one counted as
 * untested when it was compared understates it and implies a control was dropped. Neither shows up as
 * an error — only as a number a reviewer trusts.
 */
class CoverageCountingTest {

    private static CoverageLedger.Entry entry(String id, CoverageState state) {
        return new CoverageLedger.Entry("sv-1", "REQSET-V3", id, state,
                state == CoverageState.EVALUATED ? null : "reason", "Title of " + id);
    }

    private static CoverageLedger ledger(CoverageState... states) {
        var entries = new java.util.ArrayList<CoverageLedger.Entry>();
        for (int i = 0; i < states.length; i++) {
            entries.add(entry("R" + i, states[i]));
        }
        return new CoverageLedger("CMP-1", entries);
    }

    @Nested
    @DisplayName("the four reviewer-facing categories")
    class Categories {

        @Test
        @DisplayName("each state lands in exactly one count, and they sum to the total")
        void countsPartitionTheLedger() {
            var l = ledger(
                    CoverageState.EVALUATED, CoverageState.EVALUATED, CoverageState.EVALUATED,
                    CoverageState.SKIPPED, CoverageState.SKIPPED,
                    CoverageState.UNCERTAIN_APPLICABILITY,
                    CoverageState.FAILED,
                    CoverageState.PENDING);

            long notEvaluated = l.entries().stream()
                    .filter(e -> e.state() == CoverageState.PENDING
                            || e.state() == CoverageState.FAILED)
                    .count();

            assertThat(l.expectedCount()).isEqualTo(8);
            // evaluated INCLUDES the uncertain one: it was compared and produced a verdict.
            assertThat(l.evaluatedCount()).isEqualTo(4);
            assertThat(l.notApplicableCount()).isEqualTo(2);
            assertThat(l.uncertainApplicabilityCount()).isEqualTo(1);
            assertThat(notEvaluated).isEqualTo(2);

            // The partition property. If these stop summing, one category is double-counted or a
            // state is falling through unreported — and a reviewer would be reading a total that does
            // not account for every requirement.
            assertThat(l.evaluatedCount() + l.notApplicableCount() + notEvaluated)
                    .as("uncertain is a subset of evaluated, so it is not added again")
                    .isEqualTo(l.expectedCount());
        }

        @Test
        @DisplayName("an uncertain requirement counts as evaluated, not as untested")
        void uncertainIsEvaluated() {
            var l = ledger(CoverageState.UNCERTAIN_APPLICABILITY);

            // It WAS compared and the finding is real. Counting it as untested would understate
            // coverage and tell a reviewer a control was dropped when it was measured.
            assertThat(l.evaluatedCount()).isEqualTo(1);
            assertThat(l.untestedCount()).isZero();
            assertThat(l.coverageRatio()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("skipped and failed both count as untested")
        void skippedAndFailedAreUntested() {
            // Neither produced a verdict, so neither may be read as compliance (§17.6) — even though
            // SKIPPED is legitimate and FAILED is not.
            assertThat(ledger(CoverageState.SKIPPED).untestedCount()).isEqualTo(1);
            assertThat(ledger(CoverageState.FAILED).untestedCount()).isEqualTo(1);
            assertThat(ledger(CoverageState.PENDING).untestedCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        @DisplayName("only PENDING blocks completion")
        void onlyPendingBlocks() {
            assertThat(ledger(CoverageState.EVALUATED, CoverageState.SKIPPED,
                    CoverageState.FAILED, CoverageState.UNCERTAIN_APPLICABILITY).isComplete())
                    .isTrue();
            assertThat(ledger(CoverageState.EVALUATED, CoverageState.PENDING).isComplete()).isFalse();
        }

        @Test
        @DisplayName("an unresolved scope question does not stall the review")
        void uncertaintyDoesNotBlockCompletion() {
            // §17.6: doubt about applicability must not hold a review open. It is reported by its own
            // count instead, so the reviewer decides what to do about it.
            assertThat(ledger(CoverageState.UNCERTAIN_APPLICABILITY).isComplete()).isTrue();
        }

        @Test
        @DisplayName("terminal() is a positive test, so a new state defaults to blocking")
        void newStatesDefaultToBlocking() {
            // If a future state were assumed terminal, it would silently allow a comparison to report
            // COMPLETED with requirements in an unrecognised condition.
            for (var state : CoverageState.values()) {
                assertThat(state.terminal())
                        .as("%s", state)
                        .isEqualTo(state != CoverageState.PENDING);
            }
        }

        @Test
        @DisplayName("producedVerdict() is a positive test on the verdict-bearing states")
        void verdictBearingStatesAreExplicit() {
            // Enumerated rather than negated so adding a state is a DELIBERATE decision here. This
            // test caught EXTRACTION_REVIEW_REQUIRED when it was introduced, which is the behaviour
            // wanted: counting a requirement as evaluated when it was not is how a report claims
            // coverage it does not have.
            for (var state : CoverageState.values()) {
                boolean expected = state == CoverageState.EVALUATED
                        || state == CoverageState.UNCERTAIN_APPLICABILITY
                        // Compared, cited evidence, produced a reviewable result. Its exclusion from
                        // the CONCLUSION is a separate question — see conclusionEligible().
                        || state == CoverageState.EXTRACTION_REVIEW_REQUIRED;
                assertThat(state.producedVerdict()).as("%s", state).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("conclusionEligible() is narrower than producedVerdict()")
        void conclusionEligibilityIsSeparateFromCoverage() {
            // The distinction the extraction-gap design turns on. An extraction gap was MEASURED, so it
            // is covered; but a stated obligation went unassessed, so no positive compliance statement
            // may rest on it. Collapsing the two would either misreport coverage or silently let an
            // unassessed obligation support a conclusion.
            assertThat(CoverageState.EXTRACTION_REVIEW_REQUIRED.producedVerdict()).isTrue();
            assertThat(CoverageState.EXTRACTION_REVIEW_REQUIRED.conclusionEligible()).isFalse();

            for (var state : CoverageState.values()) {
                if (state.conclusionEligible()) {
                    assertThat(state.producedVerdict())
                            .as("%s cannot support a conclusion without a verdict", state)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("retryable() is a positive test, so a new state is not offered a pointless retry")
        void retryableStatesAreExplicit() {
            // An extraction gap cannot be fixed by another model call — the missing clause is upstream
            // of the model. Offering Retry spent eight model calls on CHG-08-R2 to reach the same wall.
            for (var state : CoverageState.values()) {
                boolean expected = state == CoverageState.FAILED || state == CoverageState.PENDING;
                assertThat(state.retryable()).as("%s", state).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("an empty ledger reports 0.0 coverage, never 1.0")
    void emptyLedgerIsNotFullCoverage() {
        var empty = new CoverageLedger("CMP-1", List.of());

        // "Nothing was expected" and "everything was covered" are different claims. Treating the
        // first as full coverage would report a comparison that selected no standards as complete.
        assertThat(empty.coverageRatio()).isEqualTo(0.0);
        assertThat(empty.expectedCount()).isZero();
    }

    @Test
    @DisplayName("every entry carries a title, so an uncovered requirement is nameable")
    void entriesAreNameable() {
        // "CHG-17 was not evaluated" is unactionable without knowing what CHG-17 requires — and the
        // finding that would normally carry the title is exactly what is missing.
        assertThat(ledger(CoverageState.FAILED).entries())
                .allSatisfy(e -> assertThat(e.requirementTitle()).isNotBlank());
    }
}
