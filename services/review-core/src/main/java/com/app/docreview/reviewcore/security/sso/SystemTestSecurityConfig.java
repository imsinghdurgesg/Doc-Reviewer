package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.users.AppUserService;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * A locally-signed token decoder, for automated system tests ONLY. Architecture §33.
 *
 * <p><strong>The problem this solves.</strong> Every authenticated route — upload, comparison, history,
 * coverage — is reachable only with a Google ID token, so no automated test could exercise the journey
 * a reviewer actually takes. Coverage persistence, applicability display and History all had to be
 * verified by hand, and the write path stayed unverified for a full cycle because of it.
 *
 * <p><strong>What is replaced, and what is emphatically not.</strong> Only the trust anchor: tokens are
 * verified against a locally generated RSA key instead of Google's key set. Everything downstream is the
 * production code path — hosted-domain enforcement, the email-verified claim, the invite-only allowlist,
 * role mapping, the disabled-user filter, and every {@code @PreAuthorize} on every endpoint. A test
 * harness that bypassed authorisation would prove only that the harness works.
 *
 * <p><strong>This is not demo basic auth.</strong> No seeded passwords, no {@code InMemoryUserDetails},
 * no form login. A caller still presents a signed JWT to the real {@code /api/auth/google} endpoint and
 * still has to be on the allowlist to get a session.
 *
 * <h2>Why it cannot be switched on accidentally</h2>
 *
 * Three independent conditions, all required:
 * <ol>
 *   <li>the {@code system-test} Spring profile is active;</li>
 *   <li>{@code docreview.security.mode=sso} — it augments the real chain, it does not replace it;</li>
 *   <li>{@code docreview.security.system-test-signing=enabled} must be set explicitly.</li>
 * </ol>
 *
 * <p>The key pair is generated fresh in memory at startup and never persisted, so a token minted for
 * one run cannot authenticate against another — and there is no key material in the repository, in
 * configuration, or in an image layer for anyone to find.
 */
@Configuration
@Profile("system-test")
@ConditionalOnProperty(name = "docreview.security.system-test-signing", havingValue = "enabled")
@Slf4j
public class SystemTestSecurityConfig {

    /**
     * The in-memory signing key for this process.
     *
     * <p>Generated per startup and never written anywhere. That is the containment: a leaked token is
     * useless after a restart, and there is no long-lived secret to protect.
     */
    @Bean
    public SystemTestSigningKey systemTestSigningKey() {
        log.warn("SYSTEM-TEST SIGNING IS ACTIVE. Locally-signed JWTs are accepted in place of Google "
                + "ID tokens. Authorization, the allowlist and hosted-domain checks all still apply. "
                + "This profile must never be enabled outside an automated test environment.");
        return SystemTestSigningKey.generate();
    }

    /**
     * Replaces ONLY the verifier's trust anchor.
     *
     * <p>{@code @Primary} so it wins over the production bean while the profile is active, rather than
     * the production bean being deleted or made conditional. Keeping the real definition intact means
     * the shipped path is the one that exists by default, and this is visibly an addition.
     */
    @Bean
    @Primary
    public GoogleIdTokenVerifier systemTestIdTokenVerifier(
            SsoProperties properties, AppUserService appUsers, SystemTestSigningKey key) {
        properties.validate();
        // Read from the bound properties object, not a @Value placeholder. `docreview.sso.client-id`
        // is bound BY SsoProperties rather than declared as a standalone property, so injecting the
        // placeholder fails to resolve and takes the whole context down with it.
        var clientId = properties.clientId();

        var decoder = NimbusJwtDecoder.withPublicKey(key.publicKey()).build();
        // The AUDIENCE CHECK IS KEPT. Dropping it here would mean the tests never exercise the rule
        // that a token minted for another application is rejected — and that is exactly the kind of
        // check that only ever fails in production.
        decoder.setJwtValidator(audienceValidator(clientId));
        return new GoogleIdTokenVerifier(properties, appUsers, decoder);
    }

    /** Same audience rule as production, over the test key. */
    private static OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>
            audienceValidator(String clientId) {
        return token -> {
            var audiences = token.getAudience();
            if (audiences != null && audiences.contains(clientId)) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_audience",
                            "The ID token was not issued for this application.", null));
        };
    }

    /**
     * A throwaway RSA key pair, plus the JWK set a signer needs.
     *
     * <p>Exposed as a bean so the test-token endpoint can sign with the private half without either
     * side needing to know how the key was produced.
     */
    public record SystemTestSigningKey(RSAPublicKey publicKey, RSAPrivateKey privateKey,
                                       ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext> jwkSet,
                                       String keyId) {

        static SystemTestSigningKey generate() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                var pub = (RSAPublicKey) pair.getPublic();
                var priv = (RSAPrivateKey) pair.getPrivate();
                String kid = "system-test";
                var jwk = new RSAKey.Builder(pub).privateKey(priv).keyID(kid).build();
                return new SystemTestSigningKey(pub, priv,
                        new ImmutableJWKSet<>(new JWKSet(jwk)), kid);
            } catch (Exception e) {
                // Class name only (§35), though there is no secret in this path yet.
                throw new IllegalStateException(
                        "Could not generate a system-test signing key: " + e.getClass().getSimpleName());
            }
        }
    }
}
