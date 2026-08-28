package com.app.docreview.reviewcore.standards.repository;

import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Immutable standard versions. Owned exclusively by review-core (§22.1). */
public interface StandardVersionRepository extends MongoRepository<StandardVersion, String> {

    List<StandardVersion> findByStandardIdOrderByCreatedAtDesc(String standardId);

    Optional<StandardVersion> findByStandardIdAndVersion(String standardId, String version);

    /**
     * Detects re-upload of byte-identical content (§12.1). Without this a repeated upload creates a
     * second version, and every comparison against the standard silently doubles.
     */
    Optional<StandardVersion> findByStandardIdAndContentSha256(String standardId, String sha256);

    /** Versions eligible for NEW comparisons; both conditions are required (§8.1). */
    List<StandardVersion> findByLifecycleAndIndexingState(
            StandardLifecycle lifecycle, StandardIndexingState indexingState);
}
