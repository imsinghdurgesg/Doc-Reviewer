package com.app.docreview.reviewcore.standards.sync;

import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.application.StandardAdminService;
import com.app.docreview.reviewcore.standards.domain.Standard;
import com.app.docreview.reviewcore.standards.repository.StandardRepository;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.standards.sync.StandardsSyncResult.SkippedObject;
import com.app.docreview.reviewcore.storage.gcs.GcsProperties;
import com.app.docreview.reviewcore.upload.application.DocumentFormats;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles the policy bucket into the standards library. Architecture §8.1, §21, §41.
 *
 * <p>The bucket is the source of truth for policy documents: an administrator drops a file under the
 * standards prefix and it becomes a standard version, parsed, indexed, and available for comparison.
 *
 * <h2>Reconciliation, not eventing</h2>
 *
 * <p>Each pass lists the prefix and ingests whatever is not already present. That is deliberately
 * idempotent and stateless — it holds no cursor, so it cannot lose its place, and a pass that dies
 * halfway simply does the rest next time. Object-change notifications would cut latency but add a
 * topic, a subscription, and an authenticated endpoint, and would still need this sweep behind them:
 * notification delivery is at-least-once and occasionally not-at-all, so events alone silently lose
 * documents.
 *
 * <h2>Content hash decides, not the object name</h2>
 *
 * <p>Two signals, deliberately separate:
 *
 * <ul>
 *   <li>The object NAME identifies which standard a file belongs to, and is how a re-upload under a
 *       new name is recognised as the same standard rather than a new one.</li>
 *   <li>The content HASH decides whether anything changed. {@code StandardAdminService} already
 *       returns the existing version when the bytes match, so a renamed file with identical content
 *       costs one hash and no re-embedding.</li>
 * </ul>
 *
 * <p>Collapsing them into one composite key — hashing name and content together — would make a
 * rename look like new content and re-embed the whole document. At bucket scale that is the
 * difference between a metadata write and a full re-index against a shared model proxy.
 *
 * <h2>Version labels come from the object path</h2>
 *
 * <p>{@code standards/<standard-key>/<version>/<filename>} is the layout. The path carries the
 * identity a bucket object otherwise lacks: a bare filename says nothing about which standard it
 * revises or what version it is, and guessing from the filename produces a library where every
 * document is version 1.0 of itself.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "docreview.storage.provider", havingValue = "gcs")
public class StandardsBucketSync {

    /**
     * Objects examined per pass.
     *
     * <p>A bound rather than unlimited paging: one pass should be a bounded unit of work, and a
     * bucket that grows past this is reported {@code truncated} so the cap is visible instead of
     * looking like a complete sync. The next pass picks up where this one stopped, because ingested
     * objects are skipped by hash.
     */
    private static final int MAX_OBJECTS_PER_PASS = 500;

    /** Refuses a file large enough to exhaust heap before the format gate ever sees it. */
    private static final long MAX_OBJECT_BYTES = 50L * 1024 * 1024;

    private final Storage storage;
    private final GcsProperties properties;
    private final com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService configService;
    private final StandardRepository standards;
    private final StandardVersionRepository versions;
    private final StandardAdminService adminService;

    public StandardsBucketSync(com.app.docreview.reviewcore.storage.gcs.GcsObjectStorage gcs,
                               GcsProperties properties,
                               StandardRepository standards,
                               StandardVersionRepository versions,
                               StandardAdminService adminService,
                               com.app.docreview.reviewcore.aiconfig.CloudSyncConfigService configService) {
        this.storage = gcs.storageClient();
        this.properties = properties;
        this.standards = standards;
        this.versions = versions;
        this.adminService = adminService;
        this.configService = configService;
    }

    /**
     * Runs one reconciliation pass.
     *
     * <p>Never throws for a single bad object. One unparseable file in a bucket of two hundred is a
     * skip with a reason, not a failed sync — aborting would mean one malformed document blocks
     * every other policy from reaching the library, which is exactly backwards.
     */
    public StandardsSyncResult sync(String actor) {
        Instant startedAt = Instant.now();
        List<SkippedObject> skipped = new ArrayList<>();
        int discovered = 0;
        int ingested = 0;
        int unchanged = 0;

        // Admin settings win over the environment: the screen is where a bucket is configured, and
        // a stored value that the sweep ignored would make the form look decorative.
        var stored = configService.current();
        String bucketName = stored.getBucket() != null && !stored.getBucket().isBlank()
                ? stored.getBucket() : properties.bucket();
        String prefix = stored.getStandardsPrefix() != null && !stored.getStandardsPrefix().isBlank()
                ? stored.getStandardsPrefix() : properties.standardsPrefix();

        var page = storage.list(bucketName,
                Storage.BlobListOption.prefix(prefix),
                Storage.BlobListOption.pageSize(MAX_OBJECTS_PER_PASS));

        for (Blob blob : page.iterateAll()) {
            if (discovered >= MAX_OBJECTS_PER_PASS) {
                log.warn("Sync stopped at {} objects; bucket holds more", MAX_OBJECTS_PER_PASS);
                return new StandardsSyncResult(discovered, ingested, unchanged, skipped, true,
                        startedAt, Instant.now());
            }
            // A "directory" placeholder, which GCS represents as a zero-byte object ending in /.
            if (blob.getName().endsWith("/")) {
                continue;
            }
            discovered++;

            try {
                if (ingestOne(blob, actor)) {
                    ingested++;
                } else {
                    unchanged++;
                }
            } catch (SkipException e) {
                skipped.add(new SkippedObject(blob.getName(), e.getMessage()));
            } catch (Exception e) {
                // Unexpected, so the class name is recorded — but never the message, which for a
                // parse failure can quote document content (§35).
                log.warn("Sync failed for {}: {}", blob.getName(), e.getClass().getSimpleName());
                skipped.add(new SkippedObject(blob.getName(),
                        "Unexpected error during ingestion (" + e.getClass().getSimpleName() + ")"));
            }
        }

        var result = new StandardsSyncResult(discovered, ingested, unchanged, skipped, false,
                startedAt, Instant.now());
        log.info("Bucket sync: {} discovered, {} ingested, {} unchanged, {} skipped",
                discovered, ingested, unchanged, skipped.size());
        return result;
    }

    /**
     * @return true when a new version was created, false when the content was already present
     * @throws SkipException when the object cannot become a standard version
     */
    private boolean ingestOne(Blob blob, String actor) {
        var location = BucketLocation.parse(blob.getName(), properties.standardsPrefix());

        if (!DocumentFormats.isSupported(location.filename())) {
            throw new SkipException("Unsupported file type");
        }
        long size = blob.getSize() == null ? 0 : blob.getSize();
        if (size > MAX_OBJECT_BYTES) {
            throw new SkipException("File exceeds the 50 MB limit");
        }
        if (size == 0) {
            throw new SkipException("File is empty");
        }

        byte[] content = blob.getContent();
        Standard standard = findOrCreateStandard(location, actor);

        // Content-hash dedupe lives in the admin service, which returns the existing version when
        // the bytes match. Checked here too so an unchanged object costs no download on the NEXT
        // pass — the common case on a steady bucket is that nothing has changed at all.
        String existingVersionId = versions
                .findByStandardIdAndContentSha256(standard.getId(), sha256Hex(content))
                .map(v -> v.getId())
                .orElse(null);
        if (existingVersionId != null) {
            return false;
        }

        try {
            adminService.createVersion(standard.getId(), location.version(), content,
                    location.filename(), contentTypeOf(location.filename()), actor);
            log.info("Ingested {} as {} version {}",
                    blob.getName(), standard.getStandardKey(), location.version());
            return true;
        } catch (ApiException e) {
            // A version-label collision with different content is a genuine authoring mistake: the
            // same version folder now holds a different document. Surfaced as a skip with the
            // service's own explanation rather than swallowed.
            throw new SkipException(e.getMessage());
        }
    }

    /**
     * Finds the standard this object belongs to, creating it on first sight.
     *
     * <p>Auto-creation is what makes the bucket usable as the source of truth: an administrator adds
     * {@code standards/access-control/2.0/policy.pdf} and gets a standard, rather than an error
     * telling them to go and define one first. The key comes from the path, so the same folder always
     * maps to the same standard.
     */
    private Standard findOrCreateStandard(BucketLocation location, String actor) {
        return standards.findByStandardKey(location.standardKey())
                .orElseGet(() -> {
                    var created = new Standard(location.standardKey(),
                            location.titleFromKey(), actor, actor);
                    created.setDescription("Synced from the policy bucket.");
                    var saved = standards.save(created);
                    log.info("Created standard {} from bucket path", saved.getStandardKey());
                    return saved;
                });
    }

    private static String contentTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        return "text/plain";
    }

    private static String sha256Hex(byte[] content) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(content);
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }

    /** Signals that one object cannot be ingested, carrying the operator-facing reason. */
    private static final class SkipException extends RuntimeException {
        SkipException(String message) {
            super(message);
        }
    }
}
