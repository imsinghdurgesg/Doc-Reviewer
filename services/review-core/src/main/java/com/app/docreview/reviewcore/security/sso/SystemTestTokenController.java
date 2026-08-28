package com.app.docreview.reviewcore.security.sso;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints a locally-signed ID token for automated tests. Architecture §33.
 *
 * <p><strong>Why an endpoint rather than a test-side signer.</strong> The signing key is generated in
 * the server's memory and never persisted, so a test process has no way to hold it. Asking the server
 * to sign keeps the key from ever leaving the JVM that made it.
 *
 * <p><strong>What holding this token does NOT grant.</strong> Nothing by itself. The token is exchanged
 * at the real {@code /api/auth/google} endpoint, which then applies the production hosted-domain check,
 * the invite-only allowlist and role mapping. An address not on the allowlist gets a session for
 * nothing — which is the point: the tests exercise the real authorisation model.
 *
 * <p><strong>Gated identically to the signing key.</strong> The {@code system-test} profile AND the
 * explicit signing property. Absent either, this controller does not exist, so the path 404s rather
 * than returning something a caller could probe.
 *
 * <p><strong>Internal-token protected as well.</strong> Even inside the test profile, minting requires
 * the internal service token, so the endpoint is not reachable from a browser that merely finds the URL.
 */
@RestController
@Profile("system-test")
@ConditionalOnProperty(name = "docreview.security.system-test-signing", havingValue = "enabled")
@RequiredArgsConstructor
@Slf4j
public class SystemTestTokenController {

    private final SystemTestSecurityConfig.SystemTestSigningKey key;
    /**
     * The bound properties object, not @Value placeholders.
     *
     * <p>`docreview.sso.client-id` and `hosted-domain` are bound BY SsoProperties rather than declared
     * as standalone properties, so injecting the placeholders fails to resolve and takes the whole
     * application context down. Reading them here also keeps one source of truth: a token minted with
     * a different audience or domain than the verifier expects would be rejected by our own chain.
     */
    private final SsoProperties properties;

    /** The issuer the production decoder expects, mirrored so claim shape matches Google's. */
    private static final String ISSUER = "https://accounts.google.com";

    /**
     * Signs a token for one email address.
     *
     * <p>Deliberately carries no roles: those come from the allowlist, exactly as in production. A
     * caller cannot mint themselves an ADMIN token, because the token does not decide roles.
     *
     * @param email the address to authenticate as. Must already be on the allowlist to be useful.
     */
    @PostMapping("/internal/v1/system-test/token")
    public ResponseEntity<TokenResponse> mint(@RequestParam String email) {
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(email)
                    .audience(List.of(properties.clientId()))
                    .issueTime(Date.from(now))
                    // Short-lived on purpose. A test run takes seconds, and a token that outlives the
                    // run is a credential lying around for no reason.
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("email", email)
                    .claim("email_verified", true)
                    .claim("hd", properties.hostedDomain())
                    .claim("name", email)
                    .build();

            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT).keyID(key.keyId()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key.privateKey()));

            // The EMAIL IS NOT LOGGED (§35): a mint request is an authentication event, and the address
            // may not correspond to a real user of this deployment.
            log.info("Issued a system-test ID token valid for 300s");
            return ResponseEntity.ok(new TokenResponse(jwt.serialize(), 300));

        } catch (Exception e) {
            // Class name only — a JOSE exception message can echo key material (§35).
            log.error("Could not mint a system-test token: {}", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** @param expiresInSeconds so a test can assert it does not reuse a stale token */
    public record TokenResponse(String idToken, int expiresInSeconds) {
    }
}
