package com.app.docreview.reviewcore.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated principal from Spring Security. Architecture §33.
 *
 * <p>The only class outside {@code security} that touches Spring Security types — everything else
 * depends on {@link CurrentUser}, so replacing demo authentication with organizational OIDC means
 * replacing this class alone.
 *
 * <p>Request-scoped state read on demand rather than injected per request: the bean is a stateless
 * singleton and reads {@link SecurityContextHolder} at call time, which is what makes it safe to
 * inject into singleton services.
 *
 * <p>Falls back to {@code "system"} when there is no authentication. That case is real — the recovery
 * sweep and callback handlers act with no user — and attributing their writes to {@code "system"} is
 * more honest than attributing them to whoever happened to log in last.
 */
@Component
public class SecurityContextCurrentUser implements CurrentUser {

    /** Attribution for actions with no human principal (scheduled sweeps, worker callbacks). */
    public static final String SYSTEM = "system";

    @Override
    public String id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return SYSTEM;
        }
        return authentication.getName();
    }

    @Override
    public String displayName() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return SYSTEM;
        }
        // Under SSO the principal carries the IdP's name claim; demo mode seeds usernames only, so
        // the id is the best available name there. Read reflectively via the OidcUser interface
        // rather than branching on security mode, so this class stays the single seam (§33).
        if (authentication.getPrincipal()
                instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser) {
            String fullName = oidcUser.getFullName();
            if (fullName != null && !fullName.isBlank()) {
                return fullName;
            }
        }
        return id();
    }

    @Override
    public boolean hasRole(Role role) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        // Spring Security stores roles with a ROLE_ prefix; Role does not carry it, so the
        // comparison adds it here rather than polluting the enum with a framework convention.
        String authority = "ROLE_" + role.name();
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
