package com.app.docreview.reviewcore.standards.sync;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one reconciliation pass over the bucket. Architecture §8.1, §21.
 *
 * <p><strong>Every object is accounted for.</strong> A sync that reports only what it ingested is
 * indistinguishable from one that silently skipped half the bucket — and "the policy is in the
 * bucket but the tool never saw it" is the failure mode that makes a compliance review wrong without
 * anyone noticing. Each skipped object therefore carries a reason, and truncation is reported
 * explicitly rather than left to be inferred from a count.
 *
 * @param discovered  objects seen under the prefix, before filtering
 * @param ingested    objects that produced a new standard version
 * @param unchanged   objects whose content hash already exists; the common case on a steady bucket
 * @param skipped     objects that could not be ingested, each with the reason
 * @param truncated   true when the listing hit its cap, so the bucket holds more than was examined
 * @param startedAt   when the pass began, for correlating with worker logs
 */
public record StandardsSyncResult(
        int discovered,
        int ingested,
        int unchanged,
        List<SkippedObject> skipped,
        boolean truncated,
        Instant startedAt,
        Instant completedAt
) {

    /**
     * One object the sync declined to ingest, and why.
     *
     * <p>The reason is operator-facing text, not an error code: the actions are all different —
     * convert a scanned PDF, split an oversized file, fix a name that carries no version — and a
     * code would have to be looked up to learn which.
     */
    public record SkippedObject(String objectName, String reason) {
    }

    public static StandardsSyncResult empty(Instant startedAt) {
        return new StandardsSyncResult(0, 0, 0, List.of(), false, startedAt, Instant.now());
    }
}
