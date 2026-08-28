package com.app.docreview.reviewcore.security;

/**
 * The authenticated principal, as feature code sees it. Architecture §33.
 *
 * <p>An interface so demo authentication can be replaced by organizational OIDC/SSO "without
 * changing feature code" (§33). Services depend on this; nothing outside {@code security} touches
 * Spring Security types.
 */
public interface CurrentUser {

    /** Stable user identifier, used for ownership checks and audit attribution. */
    String id();

    String displayName();

    boolean hasRole(Role role);
}
