package com.app.docreview.retrievalcomparison.spans;

import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fetches spans from document-processing over HTTP. Architecture §9.8, §22.2.
 *
 * <p>HTTP_1_1 is pinned explicitly. The JDK client defaults to HTTP_2, which against a cleartext
 * target sends an h2c upgrade preamble ({@code Connection: Upgrade} / {@code Upgrade: h2c}). Servers
 * that do not support it can mis-parse the request and answer with a validation error naming a field
 * that was in fact present — which sends debugging at the payload instead of the protocol. Every
 * service in this topology speaks HTTP/1.1, so nothing is given up.
 */
@Component
@Slf4j
public class HttpEvidenceSpanReader implements EvidenceSpanReader {

    private final RestClient client;

    public HttpEvidenceSpanReader(
            @Value("${docreview.services.document-processing.base-url:http://localhost:8081}")
            String baseUrl,
            @Value("${docreview.services.document-processing.timeout:30s}") Duration timeout,
            @Value("${docreview.internal.service-token:}") String serviceToken) {

        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // document-processing guards /internal/** with the shared service token (§9.2).
                // Without this header every span fetch is rejected 401 and byIds returns empty — and
                // because the port's contract is "absent rather than error" (§9.8), the caller sees a
                // document with no spans rather than an auth failure. That surfaces as extraction
                // reporting "no evidence spans exist", which points at the parser instead of here.
                .defaultHeader(TOKEN_HEADER, serviceToken)
                .build();
    }

    @Override
    public List<SemanticChunk> chunksForVersion(String documentVersionId) {
        try {
            var chunks = client.get()
                    .uri("/internal/documents/{versionId}/chunks", documentVersionId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<SemanticChunk>>() {});
            return chunks == null ? List.of() : chunks;
        } catch (Exception e) {
            // Empty rather than throwing, matching the rest of this port. The caller treats an empty
            // chunk set as "nothing to index", which then surfaces as a comparison that found no
            // evidence — a clearer signal than an HTTP status from deep in the pipeline.
            log.warn("Failed to load chunks for {}: {}", documentVersionId, e.getMessage());
            return List.of();
        }
    }

    /** Matches {@code InternalTokenFilter.TOKEN_HEADER} on the serving side. */
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";

    @Override
    public List<EvidenceSpan> byIds(String documentVersionId, List<String> spanIds) {
        if (spanIds == null || spanIds.isEmpty()) {
            return List.of();
        }
        try {
            var spans = client.post()
                    .uri("/internal/documents/{versionId}/spans", documentVersionId)
                    .body(Map.of("spanIds", spanIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<EvidenceSpan>>() {});
            return spans == null ? List.of() : spans;
        } catch (Exception e) {
            // Counts and the version id only — span text is confidential document content (§35).
            log.warn("Failed to load {} span(s) for {}: {}",
                    spanIds.size(), documentVersionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<EvidenceSpan> allForVersion(String documentVersionId) {
        try {
            var spans = client.get()
                    .uri("/internal/documents/{versionId}/spans", documentVersionId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<EvidenceSpan>>() {});
            return spans == null ? List.of() : spans;
        } catch (Exception e) {
            // Returns empty rather than throwing, matching byIds. The indexing runner treats an
            // empty result as "not ready to index" and fails the job with a clear reason, which is
            // more useful than an exception naming an HTTP status.
            log.warn("Failed to load spans for {}: {}", documentVersionId, e.getMessage());
            return List.of();
        }
    }
}
