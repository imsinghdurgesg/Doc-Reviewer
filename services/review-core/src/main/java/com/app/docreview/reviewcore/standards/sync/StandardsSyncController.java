package com.app.docreview.reviewcore.standards.sync;

import com.app.docreview.reviewcore.aiconfig.CloudSyncConfig;
import com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService;
import com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService.CloudSyncUpdate;
import com.app.docreview.reviewcore.security.CurrentUser;
import com.app.docreview.reviewcore.shared.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bucket sync status and manual trigger. Architecture §8.1, §41.
 *
 * <p>The sweep runs on a schedule; these exist because "is the policy I just uploaded in the tool
 * yet?" is the question an administrator actually has, and waiting out an interval to find out is
 * not an answer. The status endpoint reports what the last pass did, including everything it
 * skipped and why.
 *
 * <p>Restricted to standards administrators. A sync pass downloads and parses every new object in
 * the bucket, so triggering one is a real cost and not something a reviewer should be able to do
 * repeatedly.
 */
@RestController
@RequestMapping("/api/admin/standards/sync")
@PreAuthorize("hasAnyRole('STANDARDS_ADMIN','ADMIN')")
@RequiredArgsConstructor
public class StandardsSyncController {

    /**
     * Present in EVERY deployment, not only when the GCS provider is active.
     *
     * <p>This screen is where an administrator configures the bucket in the first place. Gating it on
     * the bucket already being configured leaves no way in — the previous version returned 404 and
     * the UI could only tell the reader to go and edit an environment file.
     *
     * <p>The sync machinery itself is still conditional, because it needs the GCS adapter. Absent, an
     * administrator can save and test settings but not run a pass, and the response says so.
     */
    private final ObjectProvider<StandardsBucketSync> sync;
    private final ObjectProvider<StandardsSyncSweep> sweep;
    private final CloudSyncConfigService configService;
    private final CurrentUser currentUser;

    /**
     * Settings, capability, and the last pass.
     *
     * <p>One response rather than three endpoints: the screen needs all of it to render, and three
     * round trips would let the form paint before it knows whether syncing is even possible.
     *
     * @param syncAvailable whether a pass can actually run. False when the deployment is not on the
     *                      GCS storage provider — the form is still editable, but the trigger is not
     *                      offered, and the UI can say why instead of failing on click.
     */
    public record CloudSyncSettings(
            boolean enabled,
            boolean syncAvailable,
            String projectId,
            String bucket,
            String standardsPrefix,
            String incomingPrefix,
            boolean archiveUploads,
            Boolean lastTestOk,
            String lastTestMessage,
            java.time.Instant lastTestedAt,
            java.time.Instant updatedAt,
            String updatedBy,
            StandardsSyncResult lastResult) {
    }

    @GetMapping
    public CloudSyncSettings status() {
        var sweepBean = sweep.getIfAvailable();
        return toSettings(configService.current(),
                sweepBean == null ? null : sweepBean.lastResult());
    }

    /** Saves settings. Always available, whatever the storage provider. */
    @PutMapping
    public CloudSyncSettings update(@RequestBody CloudSyncUpdate body) {
        var saved = configService.update(body, currentUser.id());
        var sweepBean = sweep.getIfAvailable();
        return toSettings(saved, sweepBean == null ? null : sweepBean.lastResult());
    }

    /**
     * Reads the bucket's metadata to prove the name and credentials work.
     *
     * <p>Available even without the GCS provider: verifying a bucket before switching the deployment
     * over is exactly when this is most useful.
     */
    @PostMapping("/test")
    public CloudSyncSettings testBucket() {
        var tested = configService.test(currentUser.id());
        var sweepBean = sweep.getIfAvailable();
        return toSettings(tested, sweepBean == null ? null : sweepBean.lastResult());
    }

    private CloudSyncSettings toSettings(CloudSyncConfig config, StandardsSyncResult lastResult) {
        return new CloudSyncSettings(
                config.isEnabled(),
                sync.getIfAvailable() != null,
                config.getProjectId(),
                config.getBucket(),
                config.getStandardsPrefix(),
                config.getIncomingPrefix(),
                config.isArchiveUploads(),
                config.getLastTestOk(),
                config.getLastTestMessage(),
                config.getLastTestedAt(),
                config.getUpdatedAt(),
                config.getUpdatedBy(),
                lastResult);
    }

    /**
     * Runs a pass now and returns its result.
     *
     * <p>Synchronous, unlike the rest of the platform's work. It is bounded — one listing and at
     * most the per-pass object cap — and an administrator who pressed the button is waiting for the
     * answer. Dispatching it as a job would mean polling a job id to learn whether a sync found
     * anything, which is more machinery than this needs.
     */
    @PostMapping
    public StandardsSyncResult syncNow() {
        var bean = sync.getIfAvailable();
        if (bean == null) {
            // A 409 rather than a 404: the endpoint exists and the settings are real, but the
            // deployment cannot act on them yet. Saying which makes the fix obvious.
            throw ApiException.conflict("SYNC_UNAVAILABLE",
                    "This deployment stores documents locally. Set STORAGE_PROVIDER=gcs and restart "
                            + "to run a bucket sync.");
        }
        return bean.sync(currentUser.id());
    }
}
