package com.app.docreview.reviewcore.security;

import com.app.docreview.reviewcore.security.sso.SsoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the browser how to authenticate, before it has. Architecture §33.
 *
 * <p><strong>Present in every mode, and public in every mode.</strong> That is the point: the SPA
 * must be able to ask one question — "is there a sign-in screen here?" — and get an unambiguous
 * answer without a session.
 *
 * <p>Inferring the answer from other endpoints does not work. The client previously read a 401 from
 * {@code /api/auth/session} as "SSO, not signed in", but the demo chain rejects that path with 401
 * too — before routing can report the endpoint absent — so the same status meant opposite things and
 * demo users were shown a sign-in screen that could never succeed. A declared flag has no such
 * ambiguity.
 */
@RestController
@RequiredArgsConstructor
public class AuthConfigController {

    /**
     * Whether the browser must sign in, and what with.
     *
     * @param ssoEnabled   false in demo mode: the SPA renders the application directly and HTTP Basic
     *                     has already authenticated the request
     * @param clientId     Google OAuth client id, null when SSO is off. Not a secret — the browser
     *                     sends it to Google on every attempt
     * @param hostedDomain the Workspace domain the sign-in screen states it is restricted to
     */
    public record AuthConfig(boolean ssoEnabled, String clientId, String hostedDomain) {
    }

    /**
     * ObjectProvider, not a direct dependency: {@code SsoProperties} is only bound in SSO mode, and
     * requiring it here would prevent this controller from existing in demo mode — which is exactly
     * when the client most needs an answer.
     */
    private final ObjectProvider<SsoProperties> ssoProperties;

    @Value("${docreview.security.mode:demo}")
    private String securityMode;

    @GetMapping("/api/auth/config")
    public AuthConfig config() {
        if (!"sso".equalsIgnoreCase(securityMode)) {
            return new AuthConfig(false, null, null);
        }
        var properties = ssoProperties.getIfAvailable();
        if (properties == null) {
            // Mode says sso but the properties never bound. Reporting SSO as off is the safer
            // reading: it shows the application rather than a sign-in screen with no client id,
            // and the startup validation in SsoSecurityConfig has already failed loudly.
            return new AuthConfig(false, null, null);
        }
        return new AuthConfig(true, properties.clientId(), properties.hostedDomain());
    }
}
