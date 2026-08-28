package com.app.docreview.reviewcore.security.users;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends the session of a user who has been disabled. Architecture §33.
 *
 * <p><strong>Why per request and not only at sign-in.</strong> Roles and access are resolved when a
 * session is created, and a session survives 30 minutes of inactivity. Checking only at login means a
 * revoked user keeps working for up to half an hour after an administrator removed them — which is not
 * revoking access, it is scheduling it.
 *
 * <p>Costs one indexed lookup per authenticated request. That is the price of "disabled means disabled
 * now", and it is the right trade for an authorization boundary.
 *
 * <p>Only checks DISABLED, not roles. A role change altering what someone may do is not urgent in the
 * same way — the worst case is an admin keeping admin for a few more minutes — whereas continued
 * access by someone removed from the list is the thing being prevented. Making roles live per request
 * would also mean a session's authorities disagreeing with its own security context mid-request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DisabledUserFilter extends OncePerRequestFilter {

    private final AppUserRepository users;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            var found = users.findByEmail(AppUser.normalise(auth.getName()));
            // Absent OR disabled: a user deleted from the collection is as revoked as one disabled,
            // and leaving their session alive would make deletion a weaker action than disabling.
            if (found.isEmpty() || !found.get().active()) {
                log.info("Ending session for {}: no longer authorised", auth.getName());
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Your access has been revoked. Sign in again or contact an administrator.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Skips unauthenticated and infrastructure paths.
     *
     * <p>{@code /internal/**} is service-to-service and carries no user session; the auth endpoints
     * must stay reachable so a revoked user can be told why, rather than looping on a failed request.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/internal/")
                || path.startsWith("/actuator/")
                || path.startsWith("/api/auth/");
    }
}
