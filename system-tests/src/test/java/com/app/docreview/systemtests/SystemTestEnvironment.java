package com.app.docreview.systemtests;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;

/**
 * Addresses and credentials for a running Compose stack. Architecture §36.
 *
 * <p>These tests drive the DEPLOYED topology rather than an in-process context: the seams they exist
 * to cover — the internal service token, job dispatch and callback, span reads across a service
 * boundary — only exist between separate processes. A {@code @SpringBootTest} would wire the beans
 * together directly and prove none of it.
 *
 * <p>Everything is overridable by environment variable so the same suite runs against Compose
 * locally and against a deployed environment in CI, with no code change.
 *
 * <p><strong>Only review-core is addressed for user-facing calls.</strong> §3 makes it the sole
 * browser-facing service; a system test that reached a worker directly would assert a topology the
 * product does not have. The worker URL exists only for the internal-boundary checks that
 * deliberately probe it.
 */
final class SystemTestEnvironment {

    private SystemTestEnvironment() {
    }

    /**
     * Service base URLs, matching the loopback ports {@code compose.yaml} publishes.
     *
     * <p><strong>Not 8080.</strong> That port belongs to {@code review-web}, whose SPA fallback answers
     * every unmatched path with {@code index.html} — so a health probe there returned HTTP 200 and a
     * body of HTML, and the failure read as "review-core is not reachable" when the service was in fact
     * healthy. Pointing at the app rather than the API is a mistake worth naming, because the symptom
     * looks like a down service.
     *
     * <p>Overridable by environment so a deployment that publishes different ports needs no code change.
     */
    static final String REVIEW_CORE = env("REVIEW_CORE_URL", "http://localhost:8081");
    static final String DOCUMENT_PROCESSING = env("DOCUMENT_PROCESSING_URL", "http://localhost:8082");
    static final String RETRIEVAL_COMPARISON = env("RETRIEVAL_COMPARISON_URL", "http://localhost:8083");

    static final String INTERNAL_TOKEN = env("INTERNAL_SERVICE_TOKEN", "");

    static final String ADMIN_USER = env("DEMO_ADMIN_USER", "admin");
    static final String ADMIN_PASSWORD = env("DEMO_ADMIN_PASSWORD", "");
    static final String REVIEWER_USER = env("DEMO_REVIEWER_USER", "reviewer");
    static final String REVIEWER_PASSWORD = env("DEMO_REVIEWER_PASSWORD", "");

    /**
     * How long a full upload → parse → span → index → compare journey may take.
     *
     * <p>Generous on purpose. The bound here is LibreOffice rendering and the model proxy, not the
     * application, and a tight timeout turns a slow environment into a red build that looks like a
     * product defect.
     */
    static final Duration PIPELINE_TIMEOUT = Duration.ofMinutes(env("PIPELINE_TIMEOUT_MINUTES", 5));

    /** Poll interval while waiting for a job to reach a terminal state. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * Whether the worker services answer on the host.
     *
     * <p>Under the default Compose topology they do not: §3 publishes only review-web. Probed once
     * rather than configured, because the answer depends on the topology the suite happens to be
     * pointed at and a flag would go stale against it.
     */
    static boolean workersReachable() {
        return WORKERS_REACHABLE;
    }

    private static final boolean WORKERS_REACHABLE = probe(DOCUMENT_PROCESSING);

    private static boolean probe(String baseUrl) {
        try {
            var url = java.net.URI.create(baseUrl + "/actuator/health").toURL();
            var connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            connection.getResponseCode();
            connection.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static RestClient asReviewer() {
        return basicAuthClient(REVIEW_CORE, REVIEWER_USER, REVIEWER_PASSWORD);
    }

    static RestClient asAdmin() {
        return basicAuthClient(REVIEW_CORE, ADMIN_USER, ADMIN_PASSWORD);
    }

    /**
     * The worker's internal API, carrying the shared service token.
     *
     * <p>Used by the retrieval evaluation, which measures standard selection directly rather than
     * through a comparison — §3 keeps these routes off the browser surface, so a token is the only way
     * in.
     */
    static RestClient retrievalComparison() {
        return RestClient.builder()
                .baseUrl(RETRIEVAL_COMPARISON)
                .defaultHeader("X-Internal-Service-Token",
                        env("INTERNAL_SERVICE_TOKEN", "local-dev-token"))
                .requestFactory(requestFactory())
                .build();
    }

    /**
     * A client holding a real SSO SESSION, obtained through the production login endpoint.
     * Architecture §33.
     *
     * <p><strong>Why this and not {@link #asReviewer()}.</strong> Basic auth exercises the demo
     * authentication mechanism, which is not what ships. This mints a locally-signed ID token, posts it
     * to the real {@code /api/auth/google}, and carries the resulting session cookie — so the journey
     * runs through the same hosted-domain check, invite-only allowlist, role mapping and
     * {@code @PreAuthorize} rules a production reviewer does.
     *
     * <p>Requires the {@code system-test} profile on review-core. Returns null when it is not enabled,
     * so a caller can skip rather than fail: the profile is deliberately off by default, and a suite
     * that failed without it would be red on every ordinary build.
     *
     * @param email must be on the allowlist. The token asserts no roles — those come from the
     *              allowlist, exactly as in production.
     */
    static RestClient asSsoReviewer(String email) {
        try {
            var minter = RestClient.builder()
                    .baseUrl(REVIEW_CORE)
                    .defaultHeader("X-Internal-Service-Token",
                            env("INTERNAL_SERVICE_TOKEN", "local-dev-token"))
                    .requestFactory(requestFactory())
                    .build();

            var token = minter.post()
                    .uri("/internal/v1/system-test/token?email={email}", email)
                    .retrieve()
                    .body(TestToken.class);
            if (token == null || token.idToken() == null) {
                return null;
            }

            // Exchanged at the REAL login endpoint. The Set-Cookie it returns is the session every
            // subsequent call rides on, which is what makes this a genuine authenticated journey
            // rather than a header the server was told to trust.
            var response = RestClient.builder()
                    .baseUrl(REVIEW_CORE)
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri("/api/auth/google")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("credential", token.idToken()))
                    .retrieve()
                    .toEntity(String.class);

            var cookies = response.getHeaders().get("Set-Cookie");
            if (cookies == null || cookies.isEmpty()) {
                return null;
            }
            // Name=value only: the attributes (Path, HttpOnly, SameSite) are directives for a browser
            // and a server rejects them on the way back.
            String session = cookies.getFirst().split(";", 2)[0];

            return RestClient.builder()
                    .baseUrl(REVIEW_CORE)
                    .requestFactory(requestFactory())
                    .defaultHeader("Cookie", session)
                    .build();

        } catch (Exception e) {
            // Null, not a throw: the profile being absent is the normal case and must read as "skip".
            //
            // But the REASON is recorded, because the two causes are indistinguishable from the null
            // alone and lead to opposite conclusions. A missing profile is expected; a missing
            // INTERNAL_SERVICE_TOKEN is a misconfigured run that skipped the entire journey and
            // reported success — which is a green build that proved nothing.
            lastSessionFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            return null;
        }
    }

    /** Why the last {@link #asSsoReviewer} attempt produced no session. Null before any attempt. */
    static String lastSessionFailure() {
        return lastSessionFailure;
    }

    private static volatile String lastSessionFailure;

    /**
     * True when the internal service token is available to this JVM.
     *
     * <p>Checked separately so a misconfigured run says so instead of looking like an absent profile.
     * The token is not in the shell by default — it lives in {@code .env}, which Compose reads and a
     * Maven JVM does not — so this is the likeliest reason the journey silently does nothing.
     */
    static boolean internalTokenAvailable() {
        return !env("INTERNAL_SERVICE_TOKEN", "").isBlank();
    }

    /** Mirrors SystemTestTokenController.TokenResponse. */
    record TestToken(String idToken, int expiresInSeconds) {
    }

    /** Unauthenticated, for asserting that protected routes actually reject. */
    static RestClient anonymous() {
        return RestClient.builder()
                .baseUrl(REVIEW_CORE)
                .requestFactory(requestFactory())
                .build();
    }

    private static RestClient basicAuthClient(String baseUrl, String user, String password) {
        String credentials = Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory())
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /**
     * Explicit timeouts.
     *
     * <p>The default factory waits indefinitely, so a service that accepts a connection and then
     * stalls hangs the whole suite with no indication of which call is stuck. A failed assertion
     * naming the route is diagnosable; a hung build is not.
     */
    private static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return factory;
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int env(String name, int fallback) {
        String value = System.getenv(name);
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
