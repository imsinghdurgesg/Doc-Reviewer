package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.List;
import java.util.Set;

/**
 * Verifies a Google ID token from the browser popup flow. Architecture §33.
 *
 * <p>Google Identity Services runs the consent popup client-side and hands the SPA a signed ID
 * token. The browser posts it here; this class decides whether it is genuine and who it belongs to.
 * Nothing about that token is trusted on arrival — it is attacker-supplied input until every check
 * below passes.
 *
 * <h2>What is checked, and why each matters</h2>
 *
 * <ul>
 *   <li><strong>Signature</strong>, against Google's published JWKS. Without this the token is just
 *       JSON: anyone could mint one naming any email and any domain.</li>
 *   <li><strong>{@code iss}</strong> must be Google. A validly-signed token from a different issuer
 *       proves nothing about a Stanford account.</li>
 *   <li><strong>{@code aud}</strong> must be OUR client id. This is the check people omit, and it is
 *       the one that matters most in a popup flow: a token issued for a DIFFERENT application is
 *       still correctly signed by Google and still names a real stanford.edu user. Accepting it lets
 *       any other Google app's token log in here — the "confused deputy" of ID-token verification.</li>
 *   <li><strong>{@code exp} / {@code iat}</strong>, so a captured token stops working.</li>
 *   <li><strong>{@code hd}</strong> must equal the configured Workspace domain. The popup's own
 *       domain hint is a UI convenience; this claim is the enforcement.</li>
 *   <li><strong>{@code email_verified}</strong>, because the email is what the role allowlist keys
 *       on.</li>
 * </ul>
 *
 * <p>The decoder caches Google's key set and refreshes it on rotation, which is why it is built once
 * from the issuer rather than per request.
 */
@Slf4j
public class GoogleIdTokenVerifier {

    private static final String ISSUER = "https://accounts.google.com";

    private final JwtDecoder decoder;
    private final SsoProperties properties;
    /** The allowlist. Roles come from here, not from the environment, once seeded (§33). */
    private final com.app.docreview.reviewcore.security.users.AppUserService appUsers;

    public GoogleIdTokenVerifier(
            SsoProperties properties, String clientId,
            com.app.docreview.reviewcore.security.users.AppUserService appUsers) {
        this.properties = properties;
        this.appUsers = appUsers;

        var jwtDecoder = NimbusJwtDecoder.withIssuerLocation(ISSUER).build();
        jwtDecoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                // Signature, exp and iss come from the library's defaults for this issuer.
                JwtValidators.createDefaultWithIssuer(ISSUER),
                // Audience does NOT. It has to be asserted explicitly.
                audienceValidator(clientId)));
        this.decoder = jwtDecoder;
    }

    /**
     * Builds a verifier over a SUPPLIED decoder, for the system-test profile only. Architecture §33.
     *
     * <p><strong>What this does and does not replace.</strong> Only token decoding — the mechanism by
     * which a signature is trusted. Everything downstream is the production path unchanged: the hosted
     * domain check, the email-verified check, the invite-only allowlist, and role mapping. A test that
     * bypassed those would exercise a different authorisation model from the one that ships, which is
     * worse than having no test.
     *
     * <p>Package-private and reachable only from {@code SystemTestSecurityConfig}, which is itself
     * gated on a non-default profile. There is deliberately no property that swaps the decoder inside
     * the production chain.
     */
    GoogleIdTokenVerifier(
            SsoProperties properties,
            com.app.docreview.reviewcore.security.users.AppUserService appUsers,
            JwtDecoder decoder) {
        this.properties = properties;
        this.appUsers = appUsers;
        this.decoder = decoder;
    }

    /**
     * Requires the token to have been issued FOR this application.
     *
     * <p>Spring's default validators do not check {@code aud} — there is no way for the library to
     * know which client id is ours. A token minted for another app is signed by the same Google keys
     * and carries the same issuer, so without this it verifies cleanly and authenticates a user who
     * never consented to this application.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String clientId) {
        return token -> {
            List<String> audiences = token.getAudience();
            if (audiences != null && audiences.contains(clientId)) {
                return OAuth2TokenValidatorResult.success();
            }
            // The expected value is not logged: it is configuration, and the received one may be
            // another tenant's client id.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience",
                    "The ID token was not issued for this application.", null));
        };
    }

    /**
     * @return the verified identity, never null
     * @throws JwtException when the token is not a valid, current, correctly-audienced Google token
     * @throws IllegalArgumentException when it is valid but the account is not permitted
     */
    public VerifiedIdentity verify(String idToken) {
        Jwt jwt = decoder.decode(idToken);

        String hostedDomain = jwt.getClaimAsString("hd");
        if (!properties.requireHostedDomain(hostedDomain)) {
            // Domain logged, email not: this is a rejected login and the address may belong to
            // someone with no relationship to the organization (§35).
            log.warn("Rejected popup login: hosted domain '{}' is not the configured domain",
                    hostedDomain == null ? "(absent)" : hostedDomain);
            throw new IllegalArgumentException(
                    "This application is restricted to " + properties.hostedDomain() + " accounts.");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("The token carries no email claim.");
        }

        Boolean verified = jwt.getClaimAsBoolean("email_verified");
        if (verified != null && !verified) {
            throw new IllegalArgumentException("The account's email address is not verified.");
        }

        String name = jwt.getClaimAsString("name");
        String displayName = name == null || name.isBlank() ? email : name;

        // INVITE-ONLY (§33). Google has proved who this is; the allowlist decides whether they may in.
        // Domain membership authenticates but does not authorise — ~100k accounts share the domain, and
        // documents uploaded here are not for all of them.
        //
        // The same message for unknown and disabled, deliberately: distinguishing them would tell an
        // unrecognised caller that an address IS registered, which discloses membership of the list.
        var authorised = appUsers.authorise(email, displayName);
        if (authorised.isEmpty()) {
            throw new AccessDeniedException(
                    "This account is not authorised for this application. "
                            + "Ask an administrator to add you.");
        }

        Set<Role> roles = authorised.get().getRoles();
        log.info("Popup login accepted for domain {} with roles {}", hostedDomain, roles);
        return new VerifiedIdentity(email, displayName, roles);
    }

    /** A verified Google identity and the roles it maps to. */
    public record VerifiedIdentity(String email, String displayName, Set<Role> roles) {
    }
}
