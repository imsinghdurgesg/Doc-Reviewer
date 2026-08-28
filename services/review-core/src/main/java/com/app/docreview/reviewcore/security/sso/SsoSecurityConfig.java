package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Organizational SSO via the Google sign-in popup. Architecture §33.
 *
 * <p>Selected by {@code docreview.security.mode=sso}. Demo mode remains the default and is untouched,
 * so a local run needs no identity provider.
 *
 * <h2>Why the popup rather than a server-side redirect</h2>
 *
 * <p>Google Identity Services runs the consent dialog in the browser and returns a signed ID token,
 * which the SPA posts to {@code /api/auth/google}. That needs only an <em>authorized JavaScript
 * origin</em> on the OAuth client — no client secret, and no redirect URI to register per
 * environment. The server-side authorization-code flow needs both, and every deployment URL has to be
 * registered on the client before login works there at all.
 *
 * <p>The security properties are equivalent because the token is verified server-side against
 * Google's JWKS with issuer, audience, expiry, hosted domain and email-verified all asserted (see
 * {@link GoogleIdTokenVerifier}). What the popup removes is registration friction, not verification.
 *
 * <h2>Authorization is identical to demo mode</h2>
 *
 * <p>Only the mechanism differs. §33's point is that role checks are exercised from the start rather
 * than appearing for the first time in production — so the rules below mirror {@code SecurityConfig},
 * and {@code CurrentUser} / {@code @PreAuthorize} across the codebase are unchanged.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "docreview.security.mode", havingValue = "sso")
public class SsoSecurityConfig {

    /**
     * Fails startup on an unconfigured domain, and builds the token verifier.
     *
     * <p>Validated at bean creation so a misconfigured deployment does not start. SSO that accepted
     * any Google account would be running, serving, and wrong — with nothing in the logs to say so.
     *
     * <p>The client id is equally required, and {@link SsoProperties#validate()} enforces it: it is
     * the audience every accepted token must name. Without it the audience check cannot run, and a
     * token minted for a DIFFERENT application would still verify against Google's keys and still
     * name a real stanford.edu user.
     */
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(
            SsoProperties properties,
            com.app.docreview.reviewcore.security.users.AppUserService appUsers) {
        properties.validate();
        return new GoogleIdTokenVerifier(properties, properties.clientId(), appUsers);
    }

    @Bean
    public SecurityFilterChain ssoFilterChain(
            HttpSecurity http,
            com.app.docreview.reviewcore.security.users.DisabledUserFilter disabledUsers)
            throws Exception {
        http
                // AFTER authentication, so there is a principal to check. Ends the session of a user
                // who has been disabled or removed, rather than letting it live out its 30 minutes
                // (§33).
                .addFilterAfter(disabledUsers,
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                // CSRF disabled, safe for the same reason as the demo chain: the session is
                // established by a POST carrying a token in the BODY, and every later API call is
                // same-origin from the SPA. There is no cookie-backed form for a foreign site to
                // forge, and /api/auth/google cannot be usefully forged either — an attacker would
                // need a valid Stanford ID token, in which case they can simply sign in.
                .csrf(csrf -> csrf.disable())
                // Sessions exist, but only once someone logs in.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // Liveness and readiness must answer before authentication is available, or
                        // a failing dependency makes every container look dead.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Metrics leak endpoint names and counts; scraped in-cluster, not public.
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        // The login exchange itself, or authentication can never begin. Not
                        // unprotected: it accepts nothing but a Google-signed token for this client.
                        .requestMatchers("/api/auth/google").permitAll()
                        // Answers 401 when unauthenticated, so the SPA can tell "show the sign-in
                        // button" from "you may not do this".
                        .requestMatchers("/api/auth/session", "/api/auth/config").permitAll()
                        // Enforced by InternalServiceTokenFilter ahead of this chain. permitAll here
                        // only stops Spring Security demanding a second, user-shaped credential the
                        // worker cannot supply.
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                // 401 for everything, including HTML: there is no server-side redirect to send a
                // browser to. The SPA renders its own sign-in screen on a 401.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
