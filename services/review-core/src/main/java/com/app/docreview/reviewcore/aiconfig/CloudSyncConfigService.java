package com.app.docreview.reviewcore.aiconfig;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Reads and updates bucket sync settings, and tests the connection. Architecture §41.
 *
 * <p>Available in every deployment, not only when {@code STORAGE_PROVIDER=gcs}. The Admin screen is
 * where an administrator configures the bucket in the first place, so gating it on the bucket
 * already being configured leaves no way in — which is exactly the dead end the previous version
 * produced.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CloudSyncConfigService {

    private final CloudSyncConfigRepository repository;

    public CloudSyncConfig current() {
        return repository.findById(CloudSyncConfig.SINGLETON_ID).orElseGet(CloudSyncConfig::empty);
    }

    public CloudSyncConfig update(CloudSyncUpdate update, String actor) {
        var config = current();

        if (update.enabled() != null) {
            config.setEnabled(update.enabled());
        }
        if (update.projectId() != null) {
            config.setProjectId(update.projectId().trim());
        }
        if (update.bucket() != null) {
            // gs:// is stripped: administrators paste the URI form, and the client wants a bare name.
            config.setBucket(update.bucket().trim().replaceFirst("^gs://", "").replaceAll("/+$", ""));
        }
        if (update.standardsPrefix() != null) {
            config.setStandardsPrefix(normalisePrefix(update.standardsPrefix(), "standards/"));
        }
        if (update.incomingPrefix() != null) {
            config.setIncomingPrefix(normalisePrefix(update.incomingPrefix(), "incoming/"));
        }
        if (update.archiveUploads() != null) {
            config.setArchiveUploads(update.archiveUploads());
        }

        // Discarded, not carried forward: the stored verdict describes the PREVIOUS bucket, so
        // returning it beside newly accepted settings reports a failure about configuration that no
        // longer exists. Same reasoning as AiProviderConfigService.update.
        config.setLastTestOk(null);
        config.setLastTestMessage(null);
        config.setLastTestedAt(null);

        config.setUpdatedAt(Instant.now());
        config.setUpdatedBy(actor);

        var saved = repository.save(config);
        log.info("Cloud sync configuration updated by {} (enabled={}, bucket={})",
                actor, saved.isEnabled(), saved.getBucket());
        return saved;
    }

    /**
     * Reads the bucket's metadata to prove the credentials and name work.
     *
     * <p>{@code getMetadata} rather than listing objects: it needs only {@code storage.buckets.get},
     * so a successful test does not depend on the prefix existing yet — an empty bucket is the
     * normal state before the first upload, and reporting that as a failure would be wrong.
     */
    public CloudSyncConfig test(String actor) {
        var config = current();

        if (config.getBucket() == null || config.getBucket().isBlank()) {
            return recordTest(config, false, "No bucket name is configured.");
        }

        try {
            var builder = StorageOptions.newBuilder();
            if (config.getProjectId() != null && !config.getProjectId().isBlank()) {
                builder.setProjectId(config.getProjectId());
            }
            Storage storage = builder.build().getService();

            // LISTS OBJECTS — it does not read bucket metadata.
            //
            // storage.get(bucket) needs storage.buckets.get, which is a METADATA permission that this
            // application never uses: sync calls storage.list() and the adapters read and write
            // objects. Testing with get() therefore failed on a correctly configured deployment whose
            // service account holds objectAdmin (object-level) but not bucket metadata access — the
            // test reported a broken bucket while sync would have worked, and the message it printed
            // named storage.objects.list, a permission it had not checked.
            //
            // Now the test exercises exactly what the sweep does, so a pass means sync will work and
            // a failure names something real. Least privilege is preserved: no wider grant is needed.
            var page = storage.list(config.getBucket(),
                    Storage.BlobListOption.prefix(config.getStandardsPrefix()),
                    // One object is enough to prove access. A larger page would bill and wait for
                    // data the test discards.
                    Storage.BlobListOption.pageSize(1));

            long visible = page.streamValues().limit(1).count();
            return recordTest(config, true, visible > 0
                    ? "Connected. Found objects under " + config.getStandardsPrefix() + "."
                    : "Connected, but no objects are under " + config.getStandardsPrefix()
                            + " yet. Upload a standard, or check the prefix.");

        } catch (Exception e) {
            // Class name only: a GCS error can echo the credential's identity and the object path.
            log.warn("Bucket test failed: {}", e.getClass().getSimpleName());
            return recordTest(config, false,
                    "Could not list the bucket (" + e.getClass().getSimpleName()
                            + "). Check the name and that the service account has "
                            + "storage.objects.list and storage.objects.get on it.");
        }
    }

    private CloudSyncConfig recordTest(CloudSyncConfig config, boolean ok, String message) {
        config.setLastTestOk(ok);
        config.setLastTestMessage(message);
        config.setLastTestedAt(Instant.now());
        return repository.save(config);
    }

    private static String normalisePrefix(String value, String fallback) {
        String prefix = value == null || value.isBlank() ? fallback : value.trim();
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /** Fields an administrator may change. Null means "leave unchanged". */
    public record CloudSyncUpdate(
            Boolean enabled,
            String projectId,
            String bucket,
            String standardsPrefix,
            String incomingPrefix,
            Boolean archiveUploads
    ) {
    }
}
