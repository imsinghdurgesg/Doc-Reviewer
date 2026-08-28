package com.app.docreview.reviewcore.standards.application;

import com.app.docreview.reviewcore.standards.domain.Standard;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Projects curated standard metadata from MongoDB into the vector index. Architecture §15.1, §13.4, §6.
 *
 * <p><strong>One direction only: Mongo to Qdrant.</strong> MongoDB is the source of truth for
 * department, standardFamily, and documentType. The vector payload is a derived projection, rebuilt from
 * Mongo on every indexing and re-embedding operation. Nothing reads metadata back out of Qdrant to
 * decide what is authoritative — a label edited only in the payload would be silently discarded by the
 * next re-embed, and two writable copies eventually disagree with no way to tell which is right.
 *
 * <p><strong>No re-extraction.</strong> This calls the worker's re-embed path, which reads the persisted
 * requirement set and rewrites vectors. Requirement text, ids, and provenance are untouched, so findings
 * recorded before a projection still resolve afterwards (§12.1, §19.2).
 *
 * <p>An HTTP call rather than a shared library because the vectors live in retrieval-comparison and §6
 * forbids depending on another service's jar.
 */
@Component
@Slf4j
public class VectorProjectionClient {

    private final RestClient retrievalComparison;

    public VectorProjectionClient(JobDispatchProperties properties,
                                  InternalRestClientFactory clientFactory) {
        this.retrievalComparison =
                clientFactory.create(properties.retrievalComparison().baseUrl());
    }

    /**
     * Rebuilds one version's vectors using the standard's CURRENT Mongo metadata.
     *
     * @throws RuntimeException when the worker cannot be reached or refuses. Deliberately propagated:
     *         the caller decides whether a projection failure is fatal, and for a curated edit it is
     *         not — the authoritative value is already saved.
     */
    public void reembed(Standard standard, StandardVersion version) {
        // LinkedHashMap, not Map.of: eleven pairs exceeds Map.of's ten-pair overloads, and the next
        // field added would break the build for a reason unrelated to the field.
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("standardId", nullSafe(standard.getId()));
        body.put("title", nullSafe(standard.getTitle()));
        body.put("description", nullSafe(standard.getDescription()));
        // The three ranking dimensions, read from Mongo. Sent even when empty so the worker overwrites
        // a stale payload value rather than leaving the previous one in place.
        body.put("department", nullSafe(standard.getBusinessUnit()));
        body.put("category", nullSafe(standard.getCategory()));
        body.put("standardFamily", nullSafe(standard.getStandardFamily()));
        body.put("documentType", nullSafe(standard.getDocumentType()));
        body.put("tags", standard.getTags() == null ? List.<String>of() : standard.getTags());
        // Grouping and default-version selection. The lifecycle is what decides which version of a
        // standard is offered by default — never a comparison of version labels, which are free text
        // with no ordering ("vv1" sorts before "v2").
        body.put("standardKey", nullSafe(standard.getStandardKey()));
        body.put("version", nullSafe(version.getVersion()));
        body.put("lifecycle",
                version.getLifecycle() == null ? "" : version.getLifecycle().name());

        retrievalComparison.post()
                .uri("/internal/v1/standard-vectors/{id}/reembed", version.getId())
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Projected metadata for standard {} version {} into the vector index",
                standard.getStandardKey(), version.getId());
    }

    /** Map.of rejects nulls, and an absent label is the common case rather than an error. */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
