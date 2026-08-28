package com.app.docreview.reviewcore.workflow;

import com.app.docreview.reviewcore.comparison.domain.Comparison;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A terminal comparison must be able to state what it covered. Architecture §17.6, §19.2.
 *
 * <p><strong>The invariant.</strong> A comparison created under the coverage schema cannot reach
 * COMPLETED or PARTIALLY_COMPLETED without an immutable coverage snapshot. Recording one anyway would
 * produce a review that claims to be complete while being unable to say what it measured — a
 * conclusion a reviewer would act on with no way to establish its scope.
 *
 * <p><strong>Why a stamp rather than a date or a presence check.</strong> The 48 comparisons that ran
 * before persistence legitimately have no ledger, and an invariant written afterwards must not
 * retroactively invalidate them. But "exempt" and "broken" both look like a null ledger, so the record
 * has to carry which it is.
 */
class CoverageSnapshotInvariantTest {

    private static Comparison created() {
        return new Comparison("CMP-new", "PROC-1", List.of("sv-1"), "imsingh@stanford.edu");
    }

    /** A comparison as it exists in Mongo from before the coverage schema: no stamp. */
    private static Comparison legacy() {
        var c = created();
        c.setCoverageSchemaVersion(null);
        return c;
    }

    @Nested
    @DisplayName("the exemption stamp")
    class Stamp {

        @Test
        @DisplayName("a newly created comparison is stamped, so it is NOT exempt")
        void newComparisonsAreStamped() {
            // Stamped at construction, not on completion: the invariant has to be decidable from the
            // record itself, and a stamp applied later cannot distinguish exempt from broken.
            assertThat(created().getCoverageSchemaVersion())
                    .isEqualTo(Comparison.COVERAGE_SCHEMA_V1);
        }

        @Test
        @DisplayName("a legacy comparison has no stamp and is exempt")
        void legacyComparisonsAreExempt() {
            assertThat(legacy().getCoverageSchemaVersion()).isNull();
        }

        @Test
        @DisplayName("the stamp is independent of whether a ledger is present")
        void stampDoesNotDependOnTheLedger() {
            // The two facts must stay separable. If exemption were inferred from ledger absence, a
            // new comparison whose snapshot failed to copy would silently present as legacy — which
            // is exactly the misreading the stamp exists to prevent.
            var c = created();
            assertThat(c.getCoverageLedger()).isNull();
            assertThat(c.getCoverageSchemaVersion()).isNotNull();
        }
    }

    @Nested
    @DisplayName("coverage fetch outcomes")
    class FetchOutcomes {

        @Test
        @DisplayName("a 404 is the ONLY outcome that means 'not recorded'")
        void notFoundIsTheOnlyLegacyPath() {
            var notRecorded = ComparisonFindingReader.CoverageFetch.notRecorded();

            assertThat(notRecorded.outcome())
                    .isEqualTo(ComparisonFindingReader.CoverageFetch.Outcome.NOT_RECORDED);
            assertThat(notRecorded.ledger()).isNull();
        }

        @Test
        @DisplayName("a transport failure is UNAVAILABLE, never NOT_RECORDED")
        void failuresAreDistinctFromAbsence() {
            // THE DISTINCTION THAT MATTERS. A timeout does not say the ledger does not exist — it says
            // we could not ask. Mapping it to "not recorded" would render an outage as a settled fact
            // about the review, and a reader would conclude coverage was never captured.
            for (var failure : List.of("ResourceAccessException", "HttpServerErrorException",
                    "SocketTimeoutException")) {
                var fetch = ComparisonFindingReader.CoverageFetch.unavailable(failure);
                assertThat(fetch.outcome())
                        .as("%s must not read as absence", failure)
                        .isEqualTo(ComparisonFindingReader.CoverageFetch.Outcome.UNAVAILABLE);
                assertThat(fetch.failure()).isEqualTo(failure);
            }
        }

        @Test
        @DisplayName("an empty body is UNAVAILABLE, not a ledger and not absence")
        void emptyBodyIsUnavailable() {
            // A 200 with no body is a broken response, not an answer. Treating it as absence would let
            // a serialisation bug present as a legacy comparison.
            assertThat(ComparisonFindingReader.CoverageFetch.unavailable("empty response body")
                    .outcome())
                    .isEqualTo(ComparisonFindingReader.CoverageFetch.Outcome.UNAVAILABLE);
        }

        @Test
        @DisplayName("the three outcomes are exhaustive and distinct")
        void outcomesAreDistinct() {
            // Three, not two. Collapsing any pair loses the reason the distinction was introduced.
            assertThat(ComparisonFindingReader.CoverageFetch.Outcome.values()).hasSize(3);
        }
    }
}
