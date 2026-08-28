package com.app.docreview.reviewcore.standards.application;

import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.domain.Standard;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardRepository;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.CreateStandardRequest;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.StandardResponse;
import com.app.docreview.reviewcore.standards.web.dto.StandardDtos.VersionResponse;
import com.app.docreview.reviewcore.storage.ObjectStorage;
import com.app.docreview.reviewcore.storage.StorageBuckets;
import com.app.docreview.reviewcore.upload.application.DocumentFormats;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Standards administration and immutable versioning. Architecture §8.1, Phase 2.
 *
 * <p>Three invariants are enforced here rather than left to callers, because each one is a way for
 * the system to produce a confidently wrong answer:
 *
 * <ol>
 *   <li><strong>Content immutability.</strong> A version's bytes never change. A revised standard is
 *       a new version, so a completed comparison always resolves what it actually measured (§19.2).</li>
 *   <li><strong>Duplicate detection by hash.</strong> Re-uploading identical content returns the
 *       existing version instead of creating a second one — otherwise every comparison against the
 *       standard silently doubles.</li>
 *   <li><strong>Activation requires indexing.</strong> An ACTIVE but unindexed version has no
 *       requirements, so comparisons against it return nothing and read as full compliance.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandardAdminService {

    private final StandardRepository standardRepository;
    private final StandardVersionRepository versionRepository;
    private final ObjectStorage storage;
    private final JobDispatcher jobDispatcher;
    /** Projects curated Mongo metadata into the vector index; never the reverse (§15.1). */
    private final VectorProjectionClient vectorProjection;

    public StandardResponse create(CreateStandardRequest request, String actor) {
        if (standardRepository.existsByStandardKey(request.standardKey())) {
            throw ApiException.conflict("STANDARD_KEY_EXISTS",
                    "A standard with key " + request.standardKey() + " already exists.");
        }
        var standard = new Standard(request.standardKey(), request.title(), request.owner(), actor);
        standard.setDescription(request.description());
        standard.setBusinessUnit(request.businessUnit());
        standard.setCategory(request.category());
        standard.setStandardFamily(request.standardFamily());
        standard.setDocumentType(request.documentType());
        if (request.tags() != null) {
            standard.setTags(List.copyOf(request.tags()));
        }
        var saved = standardRepository.save(standard);
        log.info("Created standard {} ({})", saved.getStandardKey(), saved.getId());
        return toResponse(saved, List.of());
    }

    /**
     * One page of standards. Architecture §8.1.
     *
     * <p>Server-side rather than fetching everything and filtering in the browser: the library grows
     * with the organization's policy set, and every standard carries its full version history in the
     * response.
     *
     * @param sort one of {@code TITLE}, {@code KEY}, {@code NEWEST}, {@code OLDEST}
     */
    public com.app.docreview.contracts.page.PageResponse<StandardResponse> search(
            String search, String sort, int page, int size) {

        // Quoted: a title can contain regex metacharacters, and an unescaped "(" is an invalid
        // pattern that fails the query rather than matching nothing.
        String term = search == null || search.isBlank()
                ? "" : java.util.regex.Pattern.quote(search.trim());

        var pageable = org.springframework.data.domain.PageRequest.of(page, size, sortFor(sort));
        var found = standardRepository.search(term, pageable);

        var content = found.getContent().stream()
                .map(s -> toResponse(s, versionRepository.findByStandardIdOrderByCreatedAtDesc(s.getId())))
                .toList();

        return com.app.docreview.contracts.page.PageResponse.of(
                content, found.getTotalElements(), found.getNumber(),
                found.getSize(), found.getTotalPages());
    }

    private org.springframework.data.domain.Sort sortFor(String sort) {
        return switch (sort == null ? "TITLE" : sort) {
            case "KEY" -> org.springframework.data.domain.Sort.by("standardKey").ascending();
            case "NEWEST" -> org.springframework.data.domain.Sort.by("updatedAt").descending();
            case "OLDEST" -> org.springframework.data.domain.Sort.by("updatedAt").ascending();
            // Title is the default because it is what the list displays; sorting by a field the
            // reader cannot see makes the order look arbitrary.
            default -> org.springframework.data.domain.Sort.by("title").ascending();
        };
    }

    public List<StandardResponse> list() {
        return standardRepository.findAll().stream()
                .map(s -> toResponse(s, versionRepository.findByStandardIdOrderByCreatedAtDesc(s.getId())))
                .toList();
    }

    public StandardResponse get(String standardId) {
        var standard = standardRepository.findById(standardId)
                .orElseThrow(() -> ApiException.notFound("Standard"));
        return toResponse(standard, versionRepository.findByStandardIdOrderByCreatedAtDesc(standardId));
    }

    /**
     * Uploads a new immutable version.
     *
     * <p>The upload is hashed BEFORE anything is stored, so duplicate detection happens without
     * writing a redundant object. Returning the existing version on a duplicate is deliberate: a
     * double-submitted form should be a no-op, not a conflict the user has to reason about.
     */
    public VersionResponse createVersion(String standardId, String version,
                                         MultipartFile file, String actor) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "The uploaded file is empty.");
        }
        String candidateName = file.getOriginalFilename() == null
                ? "source" : file.getOriginalFilename();

        return createVersion(standardId, version, readAll(file), candidateName,
                file.getContentType(), actor);
    }

    /**
     * Uploads a new immutable version from bytes already in hand.
     *
     * <p>The path a browser upload and a bucket sync SHARE. Every invariant below — the format gate,
     * content-hash dedupe, version-label collision, storage write, job dispatch — has to hold
     * identically whichever way a document arrived; a second ingestion path that reimplemented them
     * would drift, and the one that drifted would be the automated one nobody watches.
     *
     * @param filename    used for the format gate and the stored object key
     * @param contentType may be null; the format gate keys on the extension, not this
     */
    public VersionResponse createVersion(String standardId, String version,
                                         byte[] content, String filename,
                                         String contentType, String actor) {
        var standard = standardRepository.findById(standardId)
                .orElseThrow(() -> ApiException.notFound("Standard"));

        if (content == null || content.length == 0) {
            throw ApiException.badRequest("EMPTY_UPLOAD", "The uploaded file is empty.");
        }

        // Same gate as an uploaded process document. It was previously absent here, so a standard of
        // any type was accepted and then failed asynchronously during parsing — and a FAILED standard
        // cannot be activated, leaving an administrator with a broken version and no explanation.
        String candidateName = filename == null ? "source" : filename;
        DocumentFormats.requireSupported(candidateName);

        String sha256 = sha256Hex(content);

        var duplicate = versionRepository.findByStandardIdAndContentSha256(standardId, sha256);
        if (duplicate.isPresent()) {
            log.info("Re-upload of identical content for standard {}; returning existing version {}",
                    standard.getStandardKey(), duplicate.get().getVersion());
            return toVersionResponse(duplicate.get());
        }

        // A version LABEL may not be reused even with different content: two different documents
        // both called "2.4" makes every historical reference ambiguous.
        //
        // Compared NORMALISED, not exactly. An exact match let "2" through while "2.0" existed, so a
        // standard accumulated two versions an administrator reasonably read as one — and the second
        // upload looked like it had done nothing to the first. §19.2 needs a label to identify one
        // document, which it cannot do if two labels are the same version written differently.
        versionRepository.findByStandardIdOrderByCreatedAtDesc(standardId).stream()
                .filter(existing -> VersionLabel.sameVersion(existing.getVersion(), version))
                .findFirst()
                .ifPresent(existing -> {
                    // The stored label is quoted when it differs from what was submitted, so the
                    // message explains a collision that is otherwise invisible ("2 already exists"
                    // when the administrator can see only "2.0" in the list).
                    String detail = existing.getVersion().equals(version)
                            ? "Version " + version + " already exists with different content."
                            : "Version " + version + " is the same version as the existing "
                                    + existing.getVersion() + ", which has different content.";
                    throw ApiException.conflict("VERSION_EXISTS",
                            detail + " Publish a new version label instead of replacing it.");
                });

        String key = StorageBuckets.standardSource(standardId, version, candidateName);
        storage.put(StorageBuckets.STANDARDS, key, content,
                contentType == null ? "application/octet-stream" : contentType);

        var standardVersion = new StandardVersion(
                standardId, version, sha256, key, candidateName,
                contentType, content.length, actor);
        var saved = versionRepository.save(standardVersion);

        standard.setUpdatedAt(Instant.now());
        standardRepository.save(standard);

        log.info("Created version {} of standard {} ({} bytes)",
                version, standard.getStandardKey(), content.length);

        // Kick off §20.1. Only the PARSE stage is dispatched here: requirement extraction operates on
        // canonical spans (§12.1), which do not exist until document-processing has run. The indexing
        // job is dispatched from the document-processing callback, once spans are available.
        //
        // Dispatched AFTER the version is persisted so a completion callback always has a version to
        // attribute itself to (§21).
        jobDispatcher.dispatchDocumentProcessing(
                saved.getId(), DocumentType.STANDARD,
                StorageBuckets.STANDARDS, key, contentType);

        return toVersionResponse(saved);
    }

    /**
     * Changes a version's lifecycle.
     *
     * <p>Activation is gated on {@code INDEXED} (§8.1). This is the check that prevents the worst
     * failure mode available to this system: an active standard with no requirements produces a
     * comparison with no findings, which a reviewer reads as "compliant".
     */
    /**
     * Updates curated metadata and re-projects it into the vector index. Architecture §15.1, §13.4.
     *
     * <p><strong>Mongo is written first, then Qdrant is rebuilt from it.</strong> Not the reverse and
     * not both independently: a label that lives only in the vector payload is silently discarded by the
     * next re-embed, and two independent writes eventually disagree. Every indexed version of this
     * standard is re-embedded, because the payload is inherited onto the profile AND every requirement
     * chunk — updating only the profile would leave control-level retrieval ranking on stale labels.
     *
     * <p>A failed re-embed does NOT roll back the Mongo write. The curated value is the authoritative
     * record and must survive; the projection can be rebuilt by re-running the re-embed, whereas losing
     * the administrator's edit is unrecoverable.
     *
     * @param request null fields leave the stored value unchanged; an empty string clears it
     */
    public StandardResponse updateMetadata(String standardId,
                                           StandardDtos.UpdateStandardMetadataRequest request) {
        var standard = standardRepository.findById(standardId)
                .orElseThrow(() -> ApiException.notFound("Standard"));

        // Explicit null-means-unchanged. A blank string is a deliberate clear, which is why these are
        // not routed through CuratedMetadata.preserve: that guard protects against REPROCESSING
        // overwriting curation, whereas this IS curation and must be able to correct a wrong value.
        if (request.businessUnit() != null) {
            standard.setBusinessUnit(blankToNull(request.businessUnit()));
        }
        if (request.category() != null) {
            standard.setCategory(blankToNull(request.category()));
        }
        if (request.standardFamily() != null) {
            standard.setStandardFamily(blankToNull(request.standardFamily()));
        }
        if (request.documentType() != null) {
            standard.setDocumentType(blankToNull(request.documentType()));
        }
        if (request.tags() != null) {
            standard.setTags(request.tags().stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList());
        }
        var saved = standardRepository.save(standard);
        log.info("Updated curated metadata for standard {}", saved.getStandardKey());

        reprojectToVectorIndex(saved);
        return toResponse(saved,
                versionRepository.findByStandardIdOrderByCreatedAtDesc(standardId));
    }

    /**
     * Rebuilds the Qdrant payload for every indexed version of a standard, from Mongo.
     *
     * <p>Best-effort by design: see {@link #updateMetadata} on why a projection failure must not undo a
     * curated write. Failures are logged with counts and the class name only (§35).
     */
    private void reprojectToVectorIndex(Standard standard) {
        var indexedVersions = versionRepository.findByStandardIdOrderByCreatedAtDesc(standard.getId()).stream()
                .filter(v -> v.getIndexingState() == StandardIndexingState.INDEXED)
                .toList();

        int succeeded = 0;
        for (var version : indexedVersions) {
            try {
                vectorProjection.reembed(standard, version);
                succeeded++;
            } catch (Exception e) {
                log.warn("Could not re-project standard {} version {} ({}); "
                                + "curated metadata is saved and the re-embed can be retried",
                        standard.getStandardKey(), version.getId(), e.getClass().getSimpleName());
            }
        }
        log.info("Re-projected {}/{} indexed version(s) of {}",
                succeeded, indexedVersions.size(), standard.getStandardKey());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public VersionResponse updateLifecycle(String versionId, StandardLifecycle target) {
        var version = versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("Standard version"));

        if (target == StandardLifecycle.ACTIVE
                && version.getIndexingState() != StandardIndexingState.INDEXED) {
            throw ApiException.conflict("NOT_INDEXED",
                    "Version " + version.getVersion() + " is " + version.getIndexingState()
                            + " and cannot be activated. An active version with no extracted "
                            + "requirements would produce comparisons with no findings.");
        }

        version.setLifecycle(target);
        var saved = versionRepository.save(version);
        log.info("Version {} lifecycle -> {}", versionId, target);
        return toVersionResponse(saved);
    }

    /** Streams the stored source file. Used by the viewer and by the indexing pipeline. */
    public byte[] sourceContent(String versionId) {
        var version = versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("Standard version"));
        return storage.get(StorageBuckets.STANDARDS, version.getSourceObjectKey());
    }

    private byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("UPLOAD_READ_FAILED", "Could not read the uploaded file.");
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable outside a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private StandardResponse toResponse(Standard s, List<StandardVersion> versions) {
        return new StandardResponse(
                s.getId(), s.getStandardKey(), s.getTitle(), s.getDescription(),
                s.getOwner(), s.getBusinessUnit(), s.getCategory(),
                s.getStandardFamily(), s.getDocumentType(), s.getTags(),
                s.getCreatedAt(), s.getUpdatedAt(),
                versions.stream().map(this::toVersionResponse).toList());
    }

    private VersionResponse toVersionResponse(StandardVersion v) {
        return new VersionResponse(
                v.getId(), v.getStandardId(), v.getVersion(), v.getContentSha256(),
                v.getSourceFilename(), v.getSourceMediaType(), v.getSourceSizeBytes(),
                v.getEffectiveDate(), v.getReviewDate(), v.getLifecycle(), v.getIndexingState(),
                v.getIndexingMessage(),
                v.getExtractionIssues() == null ? List.of() : v.getExtractionIssues(),
                v.getCandidateCount(), v.getRejectedRequirementCount(),
                v.getRequirementSetVersion(), v.getRequirementCount(),
                v.getMaterialRequirementCount(), v.getEmbeddingModelAlias(),
                v.getEmbeddingDimensions(), v.getCreatedAt(), v.getIndexedAt(), v.comparable());
    }
}
