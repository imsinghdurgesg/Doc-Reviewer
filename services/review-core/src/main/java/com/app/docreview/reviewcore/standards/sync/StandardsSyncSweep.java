package com.app.docreview.reviewcore.standards.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the bucket reconciliation on a schedule. Architecture §21, §41.
 *
 * <p>Fixed delay rather than fixed rate, for the reason {@code JobRecoverySweep} gives: fixed delay
 * measures from the END of the previous run, so a pass that takes longer than the interval cannot
 * overlap itself. Two concurrent passes would both download and ingest the same new object — the
 * content-hash check makes that harmless rather than duplicating, but it is pointless load against a
 * metered API.
 *
 * <p>The last result is retained so an administrator can see what the most recent pass did without
 * triggering another one. A sync that reports only into the log is invisible from the UI, and
 * "did the policy I uploaded get picked up?" is the question this feature exists to answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "docreview.storage.provider", havingValue = "gcs")
public class StandardsSyncSweep {

    private final StandardsBucketSync sync;
    private final com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService configService;

    /** Last completed pass, or null before the first one finishes. */
    private final AtomicReference<StandardsSyncResult> lastResult = new AtomicReference<>();

    /**
     * @implNote {@code initialDelayString} keeps the first pass off the startup path. Ingesting a
     *     bucket during startup would delay readiness behind an unbounded amount of parsing work,
     *     and a container that fails its health check while doing useful work looks like a crash.
     */
    @Scheduled(
            initialDelayString = "${docreview.standards.sync.initial-delay:PT1M}",
            fixedDelayString = "${docreview.standards.sync.interval:PT5M}")
    public void sweep() {
        // The stored flag, not just the storage provider. An administrator turning sync off must
        // stop the sweep without a redeploy — otherwise "disabled" in the UI would be cosmetic.
        if (!configService.current().isUsable()) {
            return;
        }

        try {
            var result = sync.sync("bucket-sync");
            lastResult.set(result);

            if (result.ingested() > 0 || !result.skipped().isEmpty() || result.truncated()) {
                log.info("Standards sync: {} ingested, {} unchanged, {} skipped, truncated={}",
                        result.ingested(), result.unchanged(), result.skipped().size(),
                        result.truncated());
            }
        } catch (Exception e) {
            // Never propagate: an exception out of a @Scheduled method is logged by Spring but the
            // schedule continues, and a bucket that is briefly unreachable must not need a restart
            // to resume syncing. Class name only — a GCS error can echo object names (§35).
            log.warn("Standards sync pass failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The most recent completed pass, or null if none has finished yet. */
    public StandardsSyncResult lastResult() {
        return lastResult.get();
    }
}
