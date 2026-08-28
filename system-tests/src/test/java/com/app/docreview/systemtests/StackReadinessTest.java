package com.app.docreview.systemtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.app.docreview.systemtests.SystemTestEnvironment.DOCUMENT_PROCESSING;
import static com.app.docreview.systemtests.SystemTestEnvironment.RETRIEVAL_COMPARISON;
import static com.app.docreview.systemtests.SystemTestEnvironment.REVIEW_CORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stack is up, and its trust boundaries are where §3 says they are. Architecture §36.
 *
 * <p>Runs first. Every other system test assumes three services, three datastores and a model proxy
 * are reachable; when they are not, a readiness failure names the missing dependency instead of
 * leaving a journey test to fail deep inside an unrelated assertion.
 *
 * <p>The boundary assertions are not incidental. §3 makes review-core the only browser-facing
 * service and puts the workers behind an internal token. Both halves are load-bearing and neither is
 * exercised by any unit test, because both only exist once the services are separate processes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Compose stack readiness and trust boundaries")
class StackReadinessTest {

    private static final RestClient PLAIN = RestClient.create();

    /**
     * Only review-core is asserted reachable by default.
     *
     * <p>§3 publishes exactly one host port — review-web, which proxies {@code /api} and
     * {@code /actuator} to review-core. The workers listen only on the internal Compose network, so
     * from the host they are correctly unreachable, and asserting otherwise would fail a stack that
     * is behaving exactly as designed.
     *
     * <p>Worker health is therefore checked only when {@code DOCUMENT_PROCESSING_URL} names an
     * address that is actually published — in CI, or when a developer has added a port mapping.
     */
    @Test
    @Order(1)
    @DisplayName("the published surface reports healthy")
    void servicesAreHealthy() {
        assertHealthy(REVIEW_CORE, "review-core");

        if (SystemTestEnvironment.workersReachable()) {
            assertHealthy(DOCUMENT_PROCESSING, "document-processing");
            assertHealthy(RETRIEVAL_COMPARISON, "retrieval-comparison");
        }
    }

    @SuppressWarnings("unchecked")
    private void assertHealthy(String baseUrl, String name) {
        Map<String, Object> health;
        try {
            health = PLAIN.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new AssertionError(
                    "%s is not reachable at %s. Start the stack with `docker compose up -d`."
                            .formatted(name, baseUrl), e);
        }

        assertThat(health)
                .as("%s health payload", name)
                .isNotNull()
                .containsEntry("status", "UP");
    }

    /**
     * §33 permits liveness and readiness before authentication — a failing dependency must not make
     * every container look dead — but nothing else.
     */
    @Test
    @Order(2)
    @DisplayName("review-core requires authentication for the API")
    void apiRejectsAnonymousCallers() {
        assertThatThrownBy(() -> SystemTestEnvironment.anonymous().get()
                .uri("/api/comparisons")
                .retrieve()
                .body(String.class))
                .isInstanceOfSatisfying(HttpStatusCodeException.class, e -> assertThat(
                        e.getStatusCode().value())
                        .as("anonymous access to /api must be rejected, not served")
                        .isIn(401, 403));
    }

    /**
     * The workers' job surface is protected by a shared secret, not a user identity (§9.2, §20.3).
     *
     * <p>This asserts the negative case only. A worker route that answers an untokened request is
     * the single change that would turn an internal service into an open one, and nothing else in
     * the suite would notice — the journey tests all supply the token.
     */
    @Test
    @Order(3)
    @DisplayName("worker job surfaces reject an untokened request")
    void workersRejectUntokenedRequests() {
        // Under the default Compose topology the workers publish no host port, so the strongest
        // statement available from here is the one asserted in `workersAreNotPublished`: they cannot
        // be reached at all. The token check runs where they ARE addressable.
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemTestEnvironment.workersReachable(),
                "Workers are not published to the host under the default Compose topology (§3)");

        assertRejectsUntokened(DOCUMENT_PROCESSING, "document-processing");
        assertRejectsUntokened(RETRIEVAL_COMPARISON, "retrieval-comparison");
    }

    /**
     * The workers are not on the host network at all. Architecture §3.
     *
     * <p>The complement of the token check, and the stronger guarantee when it holds: a worker that
     * cannot be addressed from outside the Compose network needs no credential to be safe from the
     * browser. This is the property the default topology actually provides, so it is asserted rather
     * than assumed.
     */
    @Test
    @Order(4)
    @DisplayName("workers publish no host port under the default topology")
    void workersAreNotPublished() {
        org.junit.jupiter.api.Assumptions.assumeFalse(SystemTestEnvironment.workersReachable(),
                "A port mapping was added for the workers; the token check covers them instead");

        assertThatThrownBy(() -> PLAIN.get()
                .uri(DOCUMENT_PROCESSING + "/actuator/health")
                .retrieve()
                .body(String.class))
                .as("document-processing must not be reachable from the host")
                .isInstanceOf(Exception.class);
    }

    private void assertRejectsUntokened(String baseUrl, String name) {
        assertThatThrownBy(() -> PLAIN.get()
                .uri(baseUrl + "/internal/documents/PROBE-NOT-A-REAL-VERSION/spans")
                .retrieve()
                .body(String.class))
                .as("%s must not serve /internal without the service token", name)
                .isInstanceOfSatisfying(HttpStatusCodeException.class, e -> assertThat(
                        e.getStatusCode().value())
                        // 404 would mean the filter let the request through to routing, which is the
                        // failure this test exists to catch.
                        .as("%s returned %s for an untokened internal call", name, e.getStatusCode())
                        .isIn(401, 403));
    }
}
