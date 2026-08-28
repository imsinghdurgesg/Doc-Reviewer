package com.app.docreview.reviewcore.security.users;

import com.app.docreview.reviewcore.security.Role;
import com.app.docreview.reviewcore.shared.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Who may sign in, and what they may do. Architecture §33.
 *
 * <p>Most of this class is guardrails. The operations themselves are trivial — set a role, set a
 * status — and the risk is not that they fail but that they succeed in a way that locks every
 * administrator out of the application, whose only recovery is editing the database by hand.
 *
 * <p>Three refusals, each closing a distinct route to that outcome:
 *
 * <ol>
 *   <li>you cannot remove your OWN admin role, nor disable your own account — the direct route;</li>
 *   <li>the LAST active admin cannot be demoted or disabled — the same mistake, one step removed, and
 *       the one a careful person still makes because it does not look like self-harm;</li>
 *   <li>a disabled user is refused on every request, not merely at next sign-in — access that stays
 *       live for the 30-minute session window has not actually been revoked.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserService {

    private final AppUserRepository users;

    /** The record for a signed-in identity, or empty when they are not on the allowlist. */
    public Optional<AppUser> find(String email) {
        return users.findByEmail(AppUser.normalise(email));
    }

    /**
     * Resolves an authenticated identity to roles, or refuses.
     *
     * <p>Called at sign-in. A Google account proves who someone is; this decides whether they may in.
     *
     * @return the user, or empty when unknown or disabled — the caller reports both the same way,
     *         because telling an unknown caller that an address IS registered discloses membership.
     */
    public Optional<AppUser> authorise(String email, String displayName) {
        var found = users.findByEmail(AppUser.normalise(email));
        if (found.isEmpty()) {
            log.info("Sign-in refused: {} is not on the allowlist", AppUser.normalise(email));
            return Optional.empty();
        }
        var user = found.get();
        if (!user.active()) {
            log.info("Sign-in refused: {} is disabled", user.getEmail());
            return Optional.empty();
        }

        // Google is authoritative for the display name, so a rename follows the account.
        user.setDisplayName(displayName);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return Optional.of(user);
    }

    public Page<AppUser> search(String term, int page, int size) {
        boolean searching = term != null && !term.isBlank();
        // Quoted: an email can contain regex metacharacters, and an unescaped one fails the query
        // rather than matching nothing.
        String quoted = searching ? java.util.regex.Pattern.quote(term.trim()) : "";
        int bounded = Math.min(Math.max(size, 1), 100);
        return users.search(quoted, searching,
                PageRequest.of(Math.max(page, 0), bounded, Sort.by("email").ascending()));
    }

    /** Adds someone before their first sign-in, so access can be granted in advance. */
    public AppUser invite(String email, Set<Role> roles, String actor) {
        String normalised = AppUser.normalise(email);
        if (normalised.isEmpty() || !normalised.contains("@")) {
            throw ApiException.badRequest("INVALID_EMAIL", "A valid email address is required.");
        }
        if (users.existsByEmail(normalised)) {
            throw ApiException.conflict("USER_EXISTS",
                    normalised + " is already on the list. Edit their roles instead.");
        }
        var saved = users.save(new AppUser(normalised, null, roles, actor));
        log.info("User {} invited by {} with roles {}", saved.getEmail(), actor, saved.getRoles());
        return saved;
    }

    public AppUser updateRoles(String email, Set<Role> roles, String actor) {
        var user = require(email);
        boolean losingAdmin = user.hasRole(Role.ADMIN)
                && (roles == null || !roles.contains(Role.ADMIN));

        if (losingAdmin) {
            refuseSelf(user, actor, "remove your own administrator role");
            refuseLastAdmin(user, "Removing the last administrator would leave no one able to "
                    + "manage users.");
        }

        user.setRoles(roles);
        user.setUpdatedAt(Instant.now());
        user.setUpdatedBy(actor);
        var saved = users.save(user);
        log.info("Roles for {} set to {} by {}", saved.getEmail(), saved.getRoles(), actor);
        return saved;
    }

    public AppUser setStatus(String email, AppUser.Status status, String actor) {
        var user = require(email);

        if (status == AppUser.Status.DISABLED) {
            refuseSelf(user, actor, "disable your own account");
            if (user.hasRole(Role.ADMIN)) {
                refuseLastAdmin(user, "Disabling the last administrator would leave no one able to "
                        + "manage users.");
            }
        }

        user.setStatus(status);
        user.setUpdatedAt(Instant.now());
        user.setUpdatedBy(actor);
        var saved = users.save(user);
        log.info("User {} set to {} by {}", saved.getEmail(), status, actor);
        return saved;
    }

    private AppUser require(String email) {
        return users.findByEmail(AppUser.normalise(email))
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    /**
     * Refuses an administrator acting against their own access.
     *
     * <p>Not paternalism: it is the one mistake with no in-application recovery, and it is easiest to
     * make while tidying up a user list.
     */
    private static void refuseSelf(AppUser user, String actor, String action) {
        if (user.getEmail().equals(AppUser.normalise(actor))) {
            throw ApiException.badRequest("SELF_LOCKOUT",
                    "You cannot " + action + ". Ask another administrator to do it.");
        }
    }

    private void refuseLastAdmin(AppUser user, String message) {
        long activeAdmins = users.countByRolesContainingAndStatus(Role.ADMIN, AppUser.Status.ACTIVE);
        // The user being changed is still ACTIVE and still holds ADMIN at this point, so they are
        // counted — one means they are the only one.
        if (activeAdmins <= 1) {
            throw ApiException.badRequest("LAST_ADMIN", message);
        }
    }
}
