package com.app.docreview.reviewcore.aiconfig;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Bucket sync settings, set from Admin. Architecture §41.
 *
 * <p>Stored rather than environment-only, for the same reason the AI provider settings are: an
 * administrator has to be able to point the tool at a bucket, test the connection, and see the
 * result — without a redeploy and without reading a log.
 *
 * <p><strong>The enabled flag lives here, not in {@code STORAGE_PROVIDER}.</strong> Those are
 * different questions. {@code STORAGE_PROVIDER} decides where the application writes its own
 * artifacts — canonical JSON, rendered previews — and changing that at runtime would orphan
 * everything already written. Bucket SYNC is only about reading policy documents in, so it can be
 * turned on and off freely.
 *
 * <p>No {@code @Data} (§25): a generated {@code toString()} is how configuration ends up in logs.
 */
@Document(collection = "cloud_sync_config")
@Getter
public class CloudSyncConfig {

    /** Fixed id: the collection holds exactly one row, and a constant makes that structural. */
    public static final String SINGLETON_ID = "cloud-sync-config";

    @Id
    private String id = SINGLETON_ID;

    /** Whether the scheduled sweep runs and the manual trigger is offered. */
    @Setter private boolean enabled;

    @Setter private String projectId;
    @Setter private String bucket;

    /**
     * Where policy documents are read from, as
     * {@code <prefix>/<standard-key>/<version>/<filename>}.
     *
     * <p>Normalised to end with {@code /} on save: a prefix written {@code "standards"} produces
     * keys like {@code standardsaccess-control/...}, which lists cleanly under a prefix scan and is
     * invisible until someone opens the bucket.
     */
    @Setter private String standardsPrefix;

    /**
     * Where uploaded process documents would be archived. Off unless {@link #archiveUploads} is set.
     *
     * <p><strong>NOT YET IMPLEMENTED.</strong> The value is stored and returned, but nothing reads it
     * to build a key — no upload is copied anywhere. Retained as the destination for the archiving
     * feature described on {@link #archiveUploads}, which is a deliberate product decision rather
     * than an oversight.
     *
     * <p>The corresponding {@code GCS_INCOMING_PREFIX} environment variable was removed: it bound a
     * startup property that no code consumed, so it offered a way to configure something that could
     * not take effect. This runtime field is the one an administrator sees, and it is the one that
     * will be wired when archiving is built.
     */
    @Setter private String incomingPrefix;

    /**
     * Whether comparison uploads are copied to the incoming prefix.
     *
     * <p>Off by default and deliberately separate from {@code enabled}: a current-process document
     * is the customer's own material and may be confidential, so copying it to cloud storage is a
     * data-policy decision rather than a consequence of turning sync on.
     *
     * <p><strong>NOT YET IMPLEMENTED.</strong> Setting it has no effect today. It is safe in that
     * direction — the default is off, and the unbuilt behaviour is the copy, so no document is
     * archived when an administrator expects one to be. The reverse default would have been a
     * confidentiality problem.
     */
    @Setter private boolean archiveUploads;

    @Setter private Instant updatedAt;
    @Setter private String updatedBy;

    @Setter private Boolean lastTestOk;
    @Setter private String lastTestMessage;
    @Setter private Instant lastTestedAt;

    protected CloudSyncConfig() {
    }

    public static CloudSyncConfig empty() {
        var config = new CloudSyncConfig();
        config.id = SINGLETON_ID;
        config.standardsPrefix = "standards/";
        config.incomingPrefix = "incoming/";
        return config;
    }

    /** Sync can only run once a bucket is named. */
    public boolean isUsable() {
        return enabled && bucket != null && !bucket.isBlank();
    }
}
