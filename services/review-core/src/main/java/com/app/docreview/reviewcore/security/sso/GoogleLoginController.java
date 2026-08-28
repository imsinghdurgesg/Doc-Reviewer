package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Exchanges a Google popup ID token for a session. Architecture §33.
 *
 * <p>The browser runs Google Identity Services, receives a signed ID token, and posts it here. This
 * endpoint verifies it (see {@link GoogleIdTokenVerifier}) and, on success, establishes the same kind
 * of authenticated session the demo chain produces — so every {@code @PreAuthorize} and every
 * {@code CurrentUser} call downstream behaves identically under either mechanism.
 *
 * <p><strong>The session is server-side; the ID token is not reused.</strong> It is verified once and
 * discarded rather than stored or handed back to the browser as a bearer credential. A token kept
 * client-side and replayed on every request would be a long-lived credential in JavaScript's reach,
 * and its lifetime would be Google's to decide rather than ours.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "docreview.security.mode", havingValue = "sso")
public class GoogleLoginController {

    private final GoogleIdTokenVerifier verifier;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    /** What the SPA posts after the popup completes. */
    public record GoogleLoginRequest(@NotBlank String credential) {
    }

    /** What the SPA needs to render the shell: who you are and what you may do. */
    public record SessionResponse(String email, String displayName, List<String> roles) {
    }

    /**
     * Verifies the popup's ID token and starts a session.
     *
     * <p>Failure modes are deliberately distinguished. A malformed or expired token is a 401 — retry
     * by signing in again. A valid token for a non-permitted account is a 403 with the reason, because
     * retrying will never help and "invalid credentials" would send the user round the loop forever.
     */
    @PostMapping("/google")
    public ResponseEntity<?> login(@RequestBody GoogleLoginRequest body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (body == null || body.credential() == null || body.credential().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorBody("No credential was supplied."));
        }

        GoogleIdTokenVerifier.VerifiedIdentity identity;
        try {
            identity = verifier.verify(body.credential());
        } catch (JwtException e) {
            // Signature, issuer, audience or expiry failed. The reason is logged by class name only:
            // a decoder message can echo token contents (§35).
            log.warn("Rejected popup login: token verification failed ({})",
                    e.getClass().getSimpleName());
            return ResponseEntity.status(401)
                    .body(new ErrorBody("Sign-in could not be verified. Please try again."));
        } catch (IllegalArgumentException e) {
            // Genuine token, disallowed account — the message is safe and actionable.
            return ResponseEntity.status(403).body(new ErrorBody(e.getMessage()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            // NOT ON THE ALLOWLIST (§33). Genuinely authenticated by Google, but never invited.
            //
            // Previously uncaught, so the most common legitimate rejection — a real Stanford account
            // that nobody has added — surfaced as a 500 with a correlation id. A user who should have
            // been told "ask an administrator to add you" instead saw an internal error and had no
            // reason to think asking would help.
            log.info("Rejected popup login: account is authenticated but not on the allowlist");
            return ResponseEntity.status(403).body(new ErrorBody(e.getMessage()));
        }

        var authorities = identity.roles().stream()
                // Spring's ROLE_ convention is applied here rather than in the Role enum.
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(
                identity.email(), null, authorities);

        // A NEW session, and the context persisted explicitly.
        //
        // Rotating the session id on login is what prevents session fixation: an attacker who fixed a
        // session id in the victim's browser beforehand would otherwise hold a handle to the
        // now-authenticated session.
        request.getSession(true).invalidate();
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(sessionOf(identity.email(), identity.displayName(),
                identity.roles()));
    }

    /**
     * The current session, for a SPA deciding what to render on load.
     *
     * <p>401 when unauthenticated rather than an empty body: the client needs to distinguish "not
     * signed in" from "signed in with no roles", and those look identical otherwise.
     */
    @GetMapping("/session")
    public ResponseEntity<?> session() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(401).body(new ErrorBody("Not signed in."));
        }
        var roles = authentication.getAuthorities().stream()
                .map(granted -> granted.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
        return ResponseEntity.ok(new SessionResponse(
                authentication.getName(), authentication.getName(), roles));
    }

    /** Ends the session. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private SessionResponse sessionOf(String email, String displayName, Set<Role> roles) {
        return new SessionResponse(email, displayName,
                roles.stream().map(Role::name).toList());
    }

    /** RFC-7807-adjacent minimal error body, matching the shape the SPA already handles. */
    public record ErrorBody(String message) {
    }
}
