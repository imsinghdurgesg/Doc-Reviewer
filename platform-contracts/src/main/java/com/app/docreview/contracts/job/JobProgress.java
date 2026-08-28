package com.app.docreview.contracts.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A worker's mid-run progress report. Architecture §21, §20.3.
 *
 * <p><strong>Why this is separate from {@link JobCallback}.</strong> That payload requires a
 * {@code terminalState} and its receiver stamps {@code completedAt} — it is a completion report by
 * construction. Reusing it for progress would mean either relaxing the terminal-state requirement, so
 * a malformed callback could leave a job in limbo, or having review-core guess from field
 * combinations which kind of message it received. A distinct type makes the two impossible to
 * confuse: a progress report has no terminal state to set, and this endpoint cannot complete a job.
 *
 * <p><strong>Why progress needs reporting at all.</strong> The worker advanced its stage in its OWN
 * database, which §22 keeps private to it, so review-core kept serving whatever state the job had at
 * dispatch. A ten-minute comparison sat at one stage the whole time and the UI could not tell a slow
 * run from a hung one.
 *
 * <p>Lossy ON PURPOSE. These are dropped silently if they arrive late, out of order, or for a job
 * already finished: progress is a hint about a run in flight and has no value once the result exists.
 * That is what lets the sender treat delivery failures as unimportant and never retry — a progress
 * report must not slow the work it describes.
 *
 * @param jobId          the worker job this describes
 * @param jobType        which pipeline, so review-core routes without inferring
 * @param progressState  the stage the worker is in now; a label, never a terminal state
 * @param completedUnits units finished so far, where the stage has countable units
 * @param totalUnits     the denominator, when known — null before a requirement set is resolved
 */
public record JobProgress(
        @NotBlank String jobId,
        @NotNull JobType jobType,
        @NotBlank String progressState,
        @PositiveOrZero Integer completedUnits,
        @PositiveOrZero Integer totalUnits) {

    /**
     * Whether this reports a countable fraction.
     *
     * <p>Both halves are required: a numerator without a denominator is not a fraction, and a zero
     * denominator would divide by zero downstream. Callers use this to decide between showing a
     * measured percentage and showing that work is simply in progress — a distinction the UI needs,
     * because an invented percentage that never moves reads as a stuck job.
     */
    public boolean hasMeasuredProgress() {
        return totalUnits != null && totalUnits > 0 && completedUnits != null;
    }
}
