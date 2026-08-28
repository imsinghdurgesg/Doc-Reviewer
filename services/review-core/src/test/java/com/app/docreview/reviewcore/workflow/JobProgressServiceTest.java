package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.job.ComparisonState;
import com.app.docreview.contracts.job.JobProgress;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mid-run progress reporting. Architecture §21, §22.
 *
 * <p>The gap this fills: the worker advanced its stage in its OWN database, which §22 keeps private,
 * so review-core served whatever state a job had at dispatch. A ten-minute comparison reported one
 * stage throughout and the UI could not tell a slow run from a hung one.
 *
 * <p>The safety property under test is narrower than "progress works": progress must be INCAPABLE of
 * changing an outcome. These messages are unordered, unacknowledged, and may arrive after the result,
 * so anything they could overwrite is a way to corrupt a finished review.
 */
class JobProgressServiceTest {

    private ComparisonRepository comparisons;
    private JobProgressService service;

    @BeforeEach
    void setUp() {
        comparisons = Mockito.mock(ComparisonRepository.class);
        service = new JobProgressService(comparisons);
    }

    private Comparison stored(ComparisonState state) {
        var comparison = new Comparison("CMP-1", "PROC-V1", List.of("STD-V2.4"), "reviewer");
        comparison.setState(state);
        Mockito.when(comparisons.findByPublicId("CMP-1")).thenReturn(Optional.of(comparison));
        return comparison;
    }

    private static JobProgress progress(String stage, Integer done, Integer total) {
        return new JobProgress("job-1", JobType.RETRIEVAL_COMPARISON, stage, done, total);
    }

    @Nested
    @DisplayName("applying progress")
    class Applying {

        @Test
        @DisplayName("records the stage and both counters")
        void recordsStageAndCounts() {
            var comparison = stored(ComparisonState.SEARCHING_STANDARDS);

            var outcome = service.apply("CMP-1", progress("EVALUATING_REQUIREMENT", 37, 120));

            assertThat(outcome).isEqualTo(JobProgressService.Outcome.APPLIED);
            assertThat(comparison.getEvaluatedRequirements()).isEqualTo(37);
            assertThat(comparison.getTotalRequirements()).isEqualTo(120);
            assertThat(comparison.getState()).isEqualTo(ComparisonState.EVALUATING_REQUIREMENT);
        }

        @Test
        @DisplayName("never moves the count backwards")
        void countsAreMonotonic() {
            // Concurrent posts arrive out of order. A bar that jumps back reads as a restart.
            var comparison = stored(ComparisonState.EVALUATING_REQUIREMENT);
            service.apply("CMP-1", progress("EVALUATING_REQUIREMENT", 90, 120));

            service.apply("CMP-1", progress("EVALUATING_REQUIREMENT", 40, 120));

            assertThat(comparison.getEvaluatedRequirements()).isEqualTo(90);
        }
    }

    @Nested
    @DisplayName("what progress must never do")
    class Restrictions {

        @Test
        @DisplayName("cannot complete a comparison, even if a worker reports COMPLETED")
        void cannotCompleteAJob() {
            // The core restriction. Only JobCallbackService may write a terminal state, because it is
            // the only one that also records findings — a comparison marked COMPLETED with no findings
            // reads as a clean review of a document nothing was found in.
            var comparison = stored(ComparisonState.EVALUATING_REQUIREMENT);

            service.apply("CMP-1", progress("COMPLETED", 120, 120));

            assertThat(comparison.getState()).isEqualTo(ComparisonState.EVALUATING_REQUIREMENT);
            assertThat(comparison.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("cannot fail a comparison")
        void cannotFailAJob() {
            var comparison = stored(ComparisonState.EVALUATING_REQUIREMENT);

            service.apply("CMP-1", progress("FAILED", 10, 120));

            assertThat(comparison.getState()).isEqualTo(ComparisonState.EVALUATING_REQUIREMENT);
        }

        @Test
        @DisplayName("cannot reopen or overwrite a finished comparison")
        void ignoresTerminalComparisons() {
            // Progress arriving after the terminal callback is NORMAL: the two are independent HTTP
            // calls and race. Applying it would overwrite the final count with a smaller one.
            var comparison = stored(ComparisonState.COMPLETED);
            comparison.setEvaluatedRequirements(120);

            var outcome = service.apply("CMP-1", progress("EVALUATING_REQUIREMENT", 80, 120));

            assertThat(outcome).isEqualTo(JobProgressService.Outcome.IGNORED_TERMINAL);
            assertThat(comparison.getState()).isEqualTo(ComparisonState.COMPLETED);
            assertThat(comparison.getEvaluatedRequirements()).isEqualTo(120);
            Mockito.verify(comparisons, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("ignores an unknown stage but still records the counts")
        void unknownStageKeepsCounts() {
            // A worker newer than review-core. The stage is undisplayable, but the counters are still
            // meaningful, so progress degrades rather than breaking across a partial deploy.
            var comparison = stored(ComparisonState.EVALUATING_REQUIREMENT);

            service.apply("CMP-1", progress("SOME_FUTURE_STAGE", 55, 120));

            assertThat(comparison.getState()).isEqualTo(ComparisonState.EVALUATING_REQUIREMENT);
            assertThat(comparison.getEvaluatedRequirements()).isEqualTo(55);
        }
    }

    @Nested
    @DisplayName("unknown comparisons")
    class Unknown {

        @Test
        @DisplayName("reports UNKNOWN rather than throwing")
        void unknownComparison() {
            Mockito.when(comparisons.findByPublicId("CMP-missing")).thenReturn(Optional.empty());

            assertThat(service.apply("CMP-missing", progress("EVALUATING_REQUIREMENT", 1, 10)))
                    .isEqualTo(JobProgressService.Outcome.UNKNOWN);
        }

        @Test
        @DisplayName("tolerates a blank id")
        void blankId() {
            assertThat(service.apply("", progress("EVALUATING_REQUIREMENT", 1, 10)))
                    .isEqualTo(JobProgressService.Outcome.UNKNOWN);
        }
    }
}
