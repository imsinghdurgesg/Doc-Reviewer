package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.job.JobProgress;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.contracts.job.ComparisonState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records mid-run progress from a worker. Architecture §21, §22.
 *
 * <p><strong>This service can never complete, fail, or advance a comparison.</strong> It writes the
 * stage and the two counters and nothing else. That restriction is the whole design: a progress
 * message arriving late, twice, or out of order must not be able to change an outcome, so the code
 * that handles progress is kept incapable of doing so — {@link JobCallbackService} remains the only
 * writer of a terminal state.
 *
 * <p>Progress exists because §22 keeps the worker's database private to the worker. It advances its
 * own stage there, so review-core kept serving the state a job had at dispatch: a ten-minute
 * comparison reported one stage for its whole run, and the UI could not distinguish slow from stuck.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobProgressService {

    private final ComparisonRepository comparisons;

    /** What happened, so the controller can answer without the caller inspecting state. */
    public enum Outcome {
        /** Counters and stage updated. */
        APPLIED,
        /** The comparison already has a result; progress about it is meaningless now. */
        IGNORED_TERMINAL,
        /** No such comparison. Reported, not retried — a stale worker is not an emergency. */
        UNKNOWN
    }

    /**
     * Applies one progress report.
     *
     * <p>Keyed on the comparison's public ID, which the worker carries as its job's comparison ID.
     */
    public Outcome apply(String comparisonPublicId, JobProgress progress) {
        if (comparisonPublicId == null || comparisonPublicId.isBlank()) {
            return Outcome.UNKNOWN;
        }

        var found = comparisons.findByPublicId(comparisonPublicId);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN;
        }
        var comparison = found.get();

        // A finished comparison is finished. Progress arriving after the terminal callback — normal,
        // since the two races are independent HTTP calls — must not reopen it or overwrite the final
        // counts with an earlier, smaller number.
        if (isTerminal(comparison.getState())) {
            return Outcome.IGNORED_TERMINAL;
        }

        // Monotonic. Out-of-order delivery is expected on concurrent posts, and a bar that jumps
        // backwards reads as the run having restarted.
        Integer known = comparison.getEvaluatedRequirements();
        if (progress.completedUnits() != null
                && (known == null || progress.completedUnits() > known)) {
            comparison.setEvaluatedRequirements(progress.completedUnits());
        }
        if (progress.totalUnits() != null && progress.totalUnits() > 0) {
            comparison.setTotalRequirements(progress.totalUnits());
        }

        // The stage, onto the EXISTING ComparisonState members — the enum already names every
        // pipeline stage the worker reports and the UI already labels them, so a parallel field would
        // duplicate that with a second source of truth.
        //
        // Unparseable or terminal values are dropped, not applied. That is what keeps this endpoint
        // incapable of completing a job: a worker reporting "COMPLETED" as progress leaves the state
        // untouched, and only JobCallbackService can finish a comparison.
        var stage = nonTerminalState(progress.progressState());
        if (stage != null) {
            comparison.setState(stage);
        }

        comparisons.save(comparison);
        // Counts only, no document content (§35).
        log.debug("Comparison {} progress: {} {}/{}", comparisonPublicId, progress.progressState(),
                progress.completedUnits(), progress.totalUnits());
        return Outcome.APPLIED;
    }

    /**
     * The reported stage as a state, or null if it is not one this may apply.
     *
     * <p>Rejects both unknown names and terminal ones. An unknown name means the worker is newer than
     * review-core, where ignoring the stage is right — the counters still update, so progress keeps
     * working across a partial deploy.
     */
    private static ComparisonState nonTerminalState(String progressState) {
        try {
            var parsed = ComparisonState.valueOf(progressState);
            return isTerminal(parsed) ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isTerminal(ComparisonState state) {
        return state == ComparisonState.COMPLETED
                || state == ComparisonState.PARTIALLY_COMPLETED
                || state == ComparisonState.FAILED;
    }
}
