package com.app.docreview.systemtests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole reviewer journey, authenticated. Architecture §14, §17.6, §19.2, §21, §33.
 *
 * <p><strong>Why this exists.</strong> Every route a reviewer touches is session-gated, so until now
 * nothing automated could reach any of them. Coverage persistence, the applicability overlay and
 * History were all verified by hand — and the coverage write path went a full cycle unverified because
 * a manual check was the only option. This test walks the same path a person does:
 *
 * <pre>
 *   upload -> processing -> standard selection -> comparison -> coverage -> History reopen
 * </pre>
 *
 * <p><strong>The session is real.</strong> It comes from posting a locally-signed ID token to the
 * production {@code /api/auth/google}, so the hosted-domain check, the invite-only allowlist, role
 * mapping and every {@code @PreAuthorize} are exercised. Only the signature's trust anchor differs.
 *
 * <p><strong>Skipped, not failed, without the profile.</strong> The system-test profile is deliberately
 * off by default; a suite that went red without it would be red on every ordinary build.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticatedReviewJourneyTest {

    private static final String REVIEWER_EMAIL =
            SystemTestEnvironment.env("SYSTEM_TEST_EMAIL", "imsingh@stanford.edu");

    /** A small real process document, so parsing and chunking do actual work. */
    private static final Path DOCUMENT = Path.of("../demo/processes/change-release-process.pdf");

    private RestClient reviewer;

    /** Carried between ordered steps: each stage needs what the previous one produced. */
    private String documentVersionId;
    private String comparisonId;
    private Map<String, Object> coverageAtCompletion;

    @BeforeAll
    void authenticate() {
        Assumptions.assumeTrue(SystemTestEnvironment.workersReachable(),
                "workers unreachable; start the stack first");

        // A MISCONFIGURED RUN FAILS; only a genuinely absent profile skips.
        //
        // The token is not in the shell by default — it lives in .env, which Compose reads and a Maven
        // JVM does not. Without this check the whole journey silently skipped and the build reported
        // success, which is the most dangerous outcome available: a green run that measured nothing.
        assertThat(SystemTestEnvironment.internalTokenAvailable())
                .as("INTERNAL_SERVICE_TOKEN must be exported for this suite. Run with: "
                        + "env $(grep -E '^INTERNAL_SERVICE_TOKEN=' .env | xargs) ./mvnw ...")
                .isTrue();

        reviewer = SystemTestEnvironment.asSsoReviewer(REVIEWER_EMAIL);
        Assumptions.assumeTrue(reviewer != null,
                "no SSO session (" + SystemTestEnvironment.lastSessionFailure() + "). "
                        + "Start review-core with the system-test profile: "
                        + "docker compose -f compose.yaml -f compose.gcs.yaml "
                        + "-f compose.systemtest.yaml up -d review-core");
    }

    @Test
    @Order(1)
    @DisplayName("the session reaches gated routes, and anonymous callers still do not")
    void sessionIsRealAndAuthorizationStillApplies() {
        var recent = reviewer.get().uri("/api/comparisons").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(recent).as("an authenticated reviewer can list comparisons").isNotNull();

        // THE CONTROL. If this also succeeded, the profile would have disabled authorization rather
        // than swapped a trust anchor, and every assertion below would be meaningless.
        var anonymous = SystemTestEnvironment.anonymous();
        assertThat(catchStatus(() -> anonymous.get().uri("/api/comparisons").retrieve().toBodilessEntity()))
                .as("authorization must still reject an unauthenticated caller")
                .isEqualTo(401);
    }

    @Test
    @Order(2)
    @DisplayName("upload: a document is accepted and processed to spans and chunks")
    void uploadAndProcess() throws Exception {
        Assumptions.assumeTrue(Files.exists(DOCUMENT), "sample document missing: " + DOCUMENT);

        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new org.springframework.core.io.FileSystemResource(DOCUMENT));

        var uploaded = reviewer.post()
                .uri("/api/uploads")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(uploaded).isNotNull();
        documentVersionId = (String) uploaded.get("documentVersionId");
        assertThat(documentVersionId).as("upload must return a document version id").isNotBlank();

        // Re-uploading identical content returns the SAME version rather than parsing twice, so this
        // step is idempotent across reruns — which is what lets the suite run repeatedly without
        // accumulating documents.
        var ready = pollUntil(
                () -> reviewer.get().uri("/api/uploads/{id}", documentVersionId).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                d -> Boolean.TRUE.equals(d.get("readyForComparison")),
                "document never became ready for comparison");

        // Spans are what retrieval searches and what every citation resolves against. A document that
        // reports ready with none would produce a comparison where every requirement is trivially
        // missing — a green run that measured nothing.
        assertThat(asInt(ready.get("spanCount"))).as("spans extracted").isGreaterThan(0);
    }

    @Test
    @Order(3)
    @DisplayName("standard selection: a comparable version is offered and chosen explicitly")
    void selectStandardAndStartComparison() {
        Assumptions.assumeTrue(documentVersionId != null, "upload step did not run");

        var standards = reviewer.get().uri("/api/admin/standards").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(standards).isNotNull().isNotEmpty();

        // Chosen EXPLICITLY rather than left to automatic selection. The local corpus contains
        // duplicate registrations, so automatic selection is not deterministic here — and this test is
        // about the journey, not about ranking quality.
        String standardVersionId = standards.stream()
                .flatMap(s -> versionsOf(s).stream())
                .filter(v -> Boolean.TRUE.equals(v.get("comparable")))
                .map(v -> (String) v.get("id"))
                .findFirst()
                .orElse(null);
        assertThat(standardVersionId).as("at least one ACTIVE, INDEXED standard version").isNotNull();

        var created = reviewer.post()
                .uri("/api/comparisons")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("documentVersionId", documentVersionId,
                        "standardVersionIds", List.of(standardVersionId)))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(created).isNotNull();
        comparisonId = (String) created.get("publicId");
        assertThat(comparisonId).as("comparison must be addressable").isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("comparison completes, and its coverage snapshot satisfies the invariant")
    void comparisonProducesACoverageSnapshot() {
        Assumptions.assumeTrue(comparisonId != null, "comparison was not created");

        var detail = pollUntil(
                () -> reviewer.get().uri("/api/comparisons/{id}", comparisonId).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                d -> List.of("COMPLETED", "PARTIALLY_COMPLETED", "FAILED")
                        .contains(String.valueOf(d.get("status"))),
                "comparison never reached a terminal state");

        String status = String.valueOf(detail.get("status"));
        assertThat(status)
                .as("a FAILED comparison here usually means the coverage invariant refused it; "
                        + "check review-core logs for a missing snapshot")
                .isIn("COMPLETED", "PARTIALLY_COMPLETED");

        // THE INVARIANT (§17.6). A comparison created under the coverage schema cannot reach a
        // terminal state without a snapshot — the callback records FAILED rather than persisting a
        // review that cannot say what it covered. So reaching COMPLETED proves the snapshot copied.
        @SuppressWarnings("unchecked")
        var coverage = (Map<String, Object>) detail.get("coverage");
        assertThat(coverage)
                .as("a comparison created under the coverage schema must carry a snapshot")
                .isNotNull();
        assertThat(detail.get("coverageExempt"))
                .as("a newly created comparison is never exempt")
                .isEqualTo(false);

        coverageAtCompletion = coverage;

        int total = asInt(coverage.get("totalRequirements"));
        int compared = asInt(coverage.get("compared"));
        int confirmed = asInt(coverage.get("applicabilityConfirmed"));
        int reviewRequired = asInt(coverage.get("applicabilityReviewRequired"));
        int notApplicable = asInt(coverage.get("notApplicable"));
        int notEvaluated = asInt(coverage.get("notEvaluated"));

        assertThat(total).as("the standard must contribute requirements").isGreaterThan(0);

        // The partition, asserted rather than trusted. A summary whose parts do not account for the
        // whole is a coverage claim with a hole in it, and no single number reveals the hole.
        assertThat(compared + notApplicable + notEvaluated)
                .as("compared + notApplicable + notEvaluated must equal the total")
                .isEqualTo(total);
        assertThat(confirmed + reviewRequired)
                .as("applicability confirmed + review-required must equal compared")
                .isEqualTo(compared);

        // No PENDING rows: a requirement expected and never attempted is the one state that must
        // block completion (§18.4).
        @SuppressWarnings("unchecked")
        var uncovered = (List<Map<String, Object>>) coverage.get("uncovered");
        assertThat(uncovered).isNotNull();
        assertThat(uncovered.stream().map(e -> String.valueOf(e.get("state"))))
                .as("a terminal comparison must contain no PENDING coverage rows")
                .doesNotContain("PENDING");

        // Every uncovered entry is nameable and explained. "CHG-17 was not evaluated" is unactionable
        // without knowing what CHG-17 requires, and an unexplained gap is indistinguishable from a bug.
        assertThat(uncovered).allSatisfy(entry -> {
            assertThat(String.valueOf(entry.get("requirementId"))).isNotBlank();
            if ("FAILED".equals(entry.get("state"))) {
                assertThat(String.valueOf(entry.get("reason")))
                        .as("a failed requirement must say why").isNotBlank();
            }
        });
    }

    @Test
    @Order(5)
    @DisplayName("History reopen: the stored snapshot is served unchanged, not recalculated")
    void historyReopenServesTheSameSnapshot() {
        Assumptions.assumeTrue(coverageAtCompletion != null, "coverage step did not run");

        // Present in the list a reviewer actually browses.
        var recent = reviewer.get().uri("/api/comparisons").retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(recent).isNotNull();
        assertThat(recent.stream().map(c -> String.valueOf(c.get("publicId"))))
                .as("the comparison must appear in History")
                .contains(comparisonId);

        // Reopened twice. §19.2 makes a completed comparison immutable, so reopening must serve the
        // stored snapshot rather than recomputing one — a recomputed figure could drift as standards
        // are re-extracted or overrides are reviewed, silently changing what a past review claimed.
        var first = coverageOf(comparisonId);
        var second = coverageOf(comparisonId);

        assertThat(first).as("reopening is stable").isEqualTo(second);
        assertThat(first)
                .as("History must serve the coverage recorded at completion, unrecalculated")
                .isEqualTo(coverageAtCompletion);
    }

    @Test
    @Order(6)
    @DisplayName("findings carry applicability provenance, so a reviewer can tell governed from not")
    void findingsCarryApplicabilityProvenance() {
        Assumptions.assumeTrue(comparisonId != null, "comparison was not created");

        var detail = reviewer.get().uri("/api/comparisons/{id}", comparisonId).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(detail).isNotNull();

        @SuppressWarnings("unchecked")
        var references = (List<Map<String, Object>>) detail.get("references");
        assertThat(references).isNotNull().isNotEmpty();

        @SuppressWarnings("unchecked")
        var findings = (List<Map<String, Object>>) references.getFirst().get("findings");
        assertThat(findings).as("the comparison produced findings").isNotNull().isNotEmpty();

        // decidedBy is what lets a reader tell a machine-only decision from a human-governed one, and
        // what makes a reopened comparison resolve the decision that RAN rather than today's (§19.2).
        assertThat(findings).allSatisfy(f ->
                assertThat(String.valueOf(f.get("applicabilityDecidedBy")))
                        .as("every finding records what decided its applicability")
                        .startsWith("APPLICABILITY-RULES"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Map<String, Object> coverageOf(String publicId) {
        var detail = reviewer.get().uri("/api/comparisons/{id}", publicId).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(detail).isNotNull();
        @SuppressWarnings("unchecked")
        var coverage = (Map<String, Object>) detail.get("coverage");
        return coverage;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> versionsOf(Map<String, Object> standard) {
        var versions = standard.get("versions");
        return versions == null ? List.of() : (List<Map<String, Object>>) versions;
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** The HTTP status a call rejects with, so a test can assert rejection without catching noise. */
    private static int catchStatus(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return e.getStatusCode().value();
        }
    }

    /**
     * Polls until a condition holds, then returns the value that satisfied it.
     *
     * <p>Fails with the supplied message rather than hanging: a stalled pipeline that times out with
     * "comparison never reached a terminal state" is diagnosable, and a hung build is not.
     */
    private static <T> T pollUntil(java.util.function.Supplier<T> read,
                                   java.util.function.Predicate<T> done,
                                   String failure) {
        var deadline = Instant.now().plus(SystemTestEnvironment.PIPELINE_TIMEOUT);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                last = read.get();
                if (last != null && done.test(last)) {
                    return last;
                }
            } catch (Exception e) {
                // Transient during startup or mid-write. Keep polling; the deadline is the guard.
            }
            try {
                Thread.sleep(SystemTestEnvironment.POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling");
            }
        }
        throw new AssertionError(failure + " (last seen: " + last + ")");
    }
}
