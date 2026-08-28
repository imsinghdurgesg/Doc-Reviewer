package com.app.docreview.systemtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.app.docreview.systemtests.SystemTestEnvironment.PIPELINE_TIMEOUT;
import static com.app.docreview.systemtests.SystemTestEnvironment.POLL_INTERVAL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upload → parse → span → canonical view, with HTML and PDF panes agreeing. Architecture §19.1, §36.
 *
 * <p><strong>The assertion this suite exists for.</strong> The review workspace defaults to an HTML
 * pane that highlights a character range inside a parsed node, and offers a PDF pane that draws
 * stored rectangles. Both claim to show the same evidence. Nothing in the unit suites can check that
 * claim, because the two projections are produced by different services: spans and node offsets come
 * from document-processing, the canonical view is assembled in review-core, and the join between
 * them happens over HTTP.
 *
 * <p>{@code SpanNodeOffsetParityTest} pins the arithmetic in isolation. This pins the same property
 * after the data has crossed a service boundary, been serialised to JSON and back, and been filtered
 * for repeated content — which is where a field dropped from a DTO or a mis-keyed offset map would
 * actually show up.
 *
 * <p>Skipped unless {@code -Dsystem.tests=true} and a Compose stack is running.
 */
@DisplayName("Evidence resolves identically in the HTML and PDF panes")
class EvidenceParityJourneyTest {

    /**
     * Skips when demo basic-auth credentials are not configured.
     *
     * <p>This journey drives browser-facing endpoints, which require a session. On an SSO deployment
     * there is no password to present, so every request returned 401 and the suite reported two ERRORS
     * — indistinguishable from a genuine evidence-resolution defect.
     *
     * <p>An assumption rather than a silent pass: JUnit reports it as skipped, so an unrun journey can
     * never be mistaken for a passing one. Switching the deployment to demo auth to make this run is
     * NOT the answer — it locks out the SSO administrators who are using the environment.
     */
    @org.junit.jupiter.api.BeforeEach
    void requireDemoCredentials() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                SystemTestEnvironment.ADMIN_PASSWORD == null
                        || SystemTestEnvironment.ADMIN_PASSWORD.isBlank(),
                "DEMO_ADMIN_PASSWORD is not set, so this deployment uses SSO and this journey cannot "
                        + "authenticate. Skipped, NOT passed.");
    }

    /**
     * A process document with unambiguous, separable sentences.
     *
     * <p>Written inline rather than read from {@code demo/}: this test asserts on exact sentence
     * boundaries, so the fixture and the assertions must not drift apart, and a shared demo file is
     * edited for demo purposes. Plain text also skips LibreOffice, keeping the test about evidence
     * resolution rather than rendering.
     */
    private static final String PROCESS_TEXT = """
            Change Release Process

            4.1 Approval
            Changes must be approved before deployment.
            The approver must be independent of the implementing engineer.

            4.2 Records
            Approval evidence is retained for seven years in the change record.
            """;

    @Test
    @DisplayName("every hydrated span resolves to the same text through its canonical node")
    void canonicalNodeOffsetsResolveToSpanText() throws Exception {
        RestClient reviewer = SystemTestEnvironment.asReviewer();

        String documentVersionId = uploadProcessDocument(reviewer);
        awaitProcessed(reviewer, documentVersionId);

        var canonical = reviewer.get()
                .uri("/api/uploads/{id}/canonical", documentVersionId)
                .retrieve()
                .body(Map.class);

        assertThat(canonical).as("canonical view for %s", documentVersionId).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) canonical.get("nodes");
        assertThat(nodes).as("parsed nodes").isNotEmpty();

        // Repeated content is excluded by default; nothing citable may reference it.
        assertThat(nodes)
                .as("nodes are returned in reading order")
                .isSortedAccordingTo((a, b) -> Integer.compare(
                        ((Number) a.get("ordinal")).intValue(),
                        ((Number) b.get("ordinal")).intValue()));

        var spans = fetchAllSpans(documentVersionId);
        assertThat(spans).as("spans for %s", documentVersionId).isNotEmpty();

        Map<String, Map<String, Object>> nodesById = new java.util.HashMap<>();
        nodes.forEach(n -> nodesById.put((String) n.get("nodeId"), n));

        int checked = 0;
        for (Map<String, Object> span : spans) {
            String parentNodeId = (String) span.get("parentNodeId");
            Map<String, Object> node = nodesById.get(parentNodeId);
            if (node == null) {
                // Legitimate: the span's node was filtered as repeated. It must then be uncitable,
                // which the unit suite asserts; here it simply has no HTML target.
                continue;
            }

            int nodeStart = ((Number) node.get("charStart")).intValue();
            assertThat(nodeStart)
                    .as("node %s carries a resolved offset because a span points at it", parentNodeId)
                    .isGreaterThanOrEqualTo(0);

            int start = ((Number) span.get("charStart")).intValue() - nodeStart;
            int end = ((Number) span.get("charEnd")).intValue() - nodeStart;
            String nodeText = (String) node.get("text");

            assertThat(start).as("span %s starts within its node", span.get("spanId"))
                    .isBetween(0, nodeText.length());
            assertThat(end).as("span %s ends within its node", span.get("spanId"))
                    .isBetween(start, nodeText.length());

            assertThat(nodeText.substring(start, end).strip())
                    .as("HTML pane marks exactly the text span %s cites", span.get("spanId"))
                    .isEqualTo(((String) span.get("exactText")).strip());
            checked++;
        }

        assertThat(checked)
                .as("at least one span resolved through a canonical node; zero would mean this test "
                        + "asserted nothing")
                .isGreaterThan(0);
    }

    /**
     * The node offsets served to review-core agree with the spans they were derived from.
     *
     * <p>Separate from the parity assertion above because it can fail on its own: the endpoint takes
     * a minimum over spans, and a change to span emission — a new leading span, a different span
     * floor — would shift the derived offset while every individual span still looked correct.
     */
    @Test
    @DisplayName("derived node offsets are the minimum span offset within each node")
    void nodeOffsetsAgreeWithSpans() throws Exception {
        RestClient reviewer = SystemTestEnvironment.asReviewer();

        String documentVersionId = uploadProcessDocument(reviewer);
        awaitProcessed(reviewer, documentVersionId);

        var spans = fetchAllSpans(documentVersionId);
        Map<String, Integer> expected = new java.util.HashMap<>();
        for (Map<String, Object> span : spans) {
            String parent = (String) span.get("parentNodeId");
            Integer start = span.get("charStart") == null
                    ? null : ((Number) span.get("charStart")).intValue();
            if (parent != null && start != null) {
                expected.merge(parent, start, Math::min);
            }
        }

        var canonical = reviewer.get()
                .uri("/api/uploads/{id}/canonical", documentVersionId)
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) canonical.get("nodes");

        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("nodeId");
            int served = ((Number) node.get("charStart")).intValue();
            if (!expected.containsKey(nodeId)) {
                // No span points here, so the view reports -1 rather than inventing an offset.
                assertThat(served)
                        .as("node %s has no span, so no offset is claimed", nodeId)
                        .isEqualTo(-1);
                continue;
            }
            assertThat(served)
                    .as("node %s offset matches the minimum offset of its spans", nodeId)
                    .isEqualTo(expected.get(nodeId));
        }
    }

    // --- journey helpers -------------------------------------------------------------------

    private String uploadProcessDocument(RestClient reviewer) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        String filename = "change-release-process-%d.txt".formatted(Instant.now().toEpochMilli());
        form.add("file", new ByteArrayResource(PROCESS_TEXT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        var response = reviewer.post()
                .uri("/api/uploads")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);

        assertThat(response).as("upload response").isNotNull();
        String documentVersionId = (String) response.get("documentVersionId");
        assertThat(documentVersionId).as("documentVersionId from upload").isNotBlank();
        return documentVersionId;
    }

    /**
     * Polls until parsing has produced spans, or the timeout expires.
     *
     * <p>Polls the PUBLIC upload status rather than a worker, because that is the signal the browser
     * has: a test that waited on an internal state could pass while the UI still showed a document
     * as processing.
     */
    private void awaitProcessed(RestClient reviewer, String documentVersionId)
            throws InterruptedException {

        Instant deadline = Instant.now().plus(PIPELINE_TIMEOUT);
        String lastState = "(none)";

        while (Instant.now().isBefore(deadline)) {
            var status = reviewer.get()
                    .uri("/api/uploads/{id}", documentVersionId)
                    .retrieve()
                    .body(Map.class);

            lastState = String.valueOf(status == null ? null : status.get("state"));
            Object spanCount = status == null ? null : status.get("spanCount");

            if ("FAILED".equals(lastState)) {
                throw new AssertionError("Processing failed for %s: %s"
                        .formatted(documentVersionId, status.get("stateMessage")));
            }
            if (spanCount instanceof Number n && n.intValue() > 0) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }

        throw new AssertionError("Document %s did not finish processing within %s (last state: %s)"
                .formatted(documentVersionId, PIPELINE_TIMEOUT, lastState));
    }

    /**
     * Reads spans over the internal API.
     *
     * <p>The only place this suite steps outside the browser-facing surface, and deliberately: the
     * point is to compare what review-core SERVES against what document-processing STORED, so both
     * sides have to be observed independently. Using review-core's hydrated evidence for both would
     * compare a value against itself.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllSpans(String documentVersionId) {
        var client = RestClient.builder()
                .baseUrl(SystemTestEnvironment.DOCUMENT_PROCESSING)
                .defaultHeader("X-Internal-Service-Token", SystemTestEnvironment.INTERNAL_TOKEN)
                .build();

        return client.get()
                .uri("/internal/documents/{id}/spans", documentVersionId)
                .retrieve()
                .body(List.class);
    }

    /** Guard for the fixture path, if a future test reads from {@code demo/}. */
    @SuppressWarnings("unused")
    private static Path demoFile(String relative) {
        Path path = Path.of(System.getProperty("user.dir")).getParent().resolve("demo").resolve(relative);
        if (!Files.exists(path)) {
            throw new AssertionError("Missing demo fixture: " + path);
        }
        return path;
    }
}
