package com.app.docreview.reviewcore.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system-test signing profile cannot be enabled by accident. Architecture §33.
 *
 * <p><strong>What is at stake.</strong> This profile makes the server accept locally-signed JWTs in
 * place of Google ID tokens. Active in a real deployment, anyone who could reach the mint endpoint
 * could authenticate as any allowlisted address. So the gating is a security control, and it is
 * asserted structurally rather than trusted to review.
 *
 * <p><strong>Why source assertions.</strong> These are properties of the WIRING — which annotations
 * guard which beans. A behavioural test would need a full context per permutation of profile and
 * property, and would still not prove that a future edit had not removed a condition.
 */
class SystemTestProfileGatingTest {

    private static final Path CONFIG = Path.of(
            "src/main/java/com/app/docreview/reviewcore/security/sso/SystemTestSecurityConfig.java");
    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/app/docreview/reviewcore/security/sso/SystemTestTokenController.java");
    private static final Path SSO = Path.of(
            "src/main/java/com/app/docreview/reviewcore/security/sso/SsoSecurityConfig.java");

    private String source(Path path) throws IOException {
        assertThat(path).as("test must run from the module root").exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Source with comments stripped, so prose about a guard cannot satisfy an assertion. */
    private String code(Path path) throws IOException {
        return source(path).lines()
                .filter(line -> {
                    String t = line.strip();
                    return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Nested
    @DisplayName("both test-only beans are doubly gated")
    class Gating {

        @Test
        @DisplayName("the signing config requires the profile AND an explicit property")
        void configRequiresProfileAndProperty() throws IOException {
            var code = code(CONFIG);

            // Either alone is insufficient. A profile can be set by an operator copying a command
            // line; a property named "system-test-signing" cannot plausibly be set by accident.
            assertThat(code).contains("@Profile(\"system-test\")");
            assertThat(code).contains("docreview.security.system-test-signing");
            assertThat(code).contains("havingValue = \"enabled\"");
        }

        @Test
        @DisplayName("the mint endpoint carries the same two guards")
        void controllerRequiresProfileAndProperty() throws IOException {
            var code = code(CONTROLLER);

            // Gated independently rather than relying on the signing key bean being absent: a missing
            // dependency would fail at startup, but a controller that exists and 500s is still a
            // reachable, probeable endpoint.
            assertThat(code).contains("@Profile(\"system-test\")");
            assertThat(code).contains("docreview.security.system-test-signing");
        }

        @Test
        @DisplayName("neither guard has a default that would enable it")
        void noEnablingDefault() throws IOException {
            // matchIfMissing=true would make the property optional, which would collapse two
            // conditions into one.
            assertThat(code(CONFIG)).doesNotContain("matchIfMissing = true");
            assertThat(code(CONTROLLER)).doesNotContain("matchIfMissing = true");
        }
    }

    @Nested
    @DisplayName("it augments the production model, never bypasses it")
    class NoBypass {

        @Test
        @DisplayName("the audience check is kept, not dropped for convenience")
        void audienceStillValidated() throws IOException {
            // The rule that a token minted for another application is rejected. Dropping it in tests
            // would mean the one check that only ever fails in production is never exercised.
            assertThat(code(CONFIG)).contains("audienceValidator");
            assertThat(code(CONFIG)).contains("invalid_audience");
        }

        @Test
        @DisplayName("the real verifier is reused, so domain and allowlist checks still run")
        void reusesProductionVerifier() throws IOException {
            var code = code(CONFIG);

            // Constructing the REAL GoogleIdTokenVerifier is what keeps the hosted-domain check, the
            // email_verified check, the invite-only allowlist and role mapping in the path. A
            // bespoke test verifier would exercise a different authorisation model from the shipped one.
            assertThat(code).contains("new GoogleIdTokenVerifier(");
            assertThat(code).contains("AppUserService");
        }

        @Test
        @DisplayName("the minted token carries no roles")
        void tokenCannotGrantRoles() throws IOException {
            var code = code(CONTROLLER);

            // Roles come from the allowlist, exactly as in production. A token that could assert its
            // own roles would let a caller mint themselves an administrator.
            assertThat(code).doesNotContain("\"roles\"");
            assertThat(code).doesNotContain("Role.ADMIN");
            assertThat(code).doesNotContain("STANDARDS_ADMIN");
        }

        @Test
        @DisplayName("demo basic auth is NOT reintroduced")
        void noDemoAuthResurrection() throws IOException {
            // Explicitly ruled out. The point of this profile is a signed-token path that exercises
            // the real chain; seeded passwords would be a second, weaker authentication mechanism
            // living in the same deployment.
            for (var path : new Path[]{CONFIG, CONTROLLER}) {
                var code = code(path);
                assertThat(code).doesNotContain("InMemoryUserDetailsManager");
                assertThat(code).doesNotContain("formLogin");
                assertThat(code).doesNotContain("httpBasic");
                assertThat(code).doesNotContain("PasswordEncoder");
            }
        }

        @Test
        @DisplayName("the production SSO chain is untouched by this profile")
        void productionChainUnchanged() throws IOException {
            var sso = code(SSO);

            // Still gated on mode=sso only, with no awareness of the test profile. The test config
            // overrides one bean with @Primary rather than editing the shipped chain, so the default
            // path is the one that exists without any profile at all.
            assertThat(sso).contains("havingValue = \"sso\"");
            assertThat(sso).doesNotContain("system-test");
        }
    }

    @Test
    @DisplayName("the signing key is generated in memory, never read from configuration")
    void keyIsEphemeral() throws IOException {
        var code = code(CONFIG);

        // No key material in the repository, in configuration, or in an image layer — and a token
        // minted for one run cannot authenticate against another.
        assertThat(code).contains("KeyPairGenerator");
        assertThat(code).doesNotContain("@Value(\"${docreview.security.system-test-key");
        assertThat(code).doesNotContain("PRIVATE KEY");
    }
}
