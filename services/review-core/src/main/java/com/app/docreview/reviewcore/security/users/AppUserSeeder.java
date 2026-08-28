package com.app.docreview.reviewcore.security.users;

import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.security.Role;
import com.app.docreview.reviewcore.security.sso.SsoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Populates the allowlist on first start, so enabling invite-only cannot lock everyone out.
 * Architecture §33.
 *
 * <p><strong>The problem this solves.</strong> Access is invite-only: only listed users may sign in.
 * Deployed against an empty collection that means NOBODY can sign in, including the administrator who
 * would add the first user — and the only recovery is editing Mongo by hand.
 *
 * <p>So on the first start the collection is seeded from what the deployment already knows:
 *
 * <ul>
 *   <li>{@code SSO_ADMIN_EMAILS} and {@code SSO_STANDARDS_ADMIN_EMAILS} — the people who were
 *       administrators before this collection existed;</li>
 *   <li>{@code comparisons.createdBy} — everyone who has actually used the tool, as REVIEWER, so
 *       nobody working yesterday is refused today.</li>
 * </ul>
 *
 * <p><strong>Runs ONCE.</strong> Guarded on the collection being empty, not on a flag: re-seeding on
 * every boot would resurrect users an administrator had deliberately removed, which is worse than not
 * seeding at all. After the first start the environment lists are never consulted again — they are a
 * bootstrap, not a second source of truth that can silently disagree with the UI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppUserSeeder {

    private final AppUserRepository users;
    private final ComparisonRepository comparisons;
    private final SsoProperties sso;

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (users.count() > 0) {
            return;
        }

        int admins = 0;
        // The properties are comma-separated strings, being environment variables.
        for (String email : split(sso.adminEmails())) {
            if (upsert(email, Set.of(Role.ADMIN, Role.STANDARDS_ADMIN))) {
                admins++;
            }
        }
        for (String email : split(sso.standardsEmails())) {
            upsert(email, Set.of(Role.STANDARDS_ADMIN));
        }

        // Anyone who ran a review. Read from the authoritative record of who used the tool rather than
        // from a list someone remembered to maintain.
        int reviewers = 0;
        for (var comparison : comparisons.findAll()) {
            if (upsert(comparison.getCreatedBy(), Set.of(Role.REVIEWER))) {
                reviewers++;
            }
        }

        if (admins == 0) {
            // Loud, because the deployment is now unusable and the reason is not obvious from a
            // sign-in failure. Better to say so at boot than to let three people discover it.
            log.error("User allowlist seeded with NO administrator. Nobody can manage users. Set "
                    + "SSO_ADMIN_EMAILS and restart, or insert a user record directly.");
        } else {
            log.info("Seeded user allowlist: {} administrator(s), {} reviewer(s)", admins, reviewers);
        }
    }

    private static java.util.List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** @return true when a record was created; false when the address was blank or already present. */
    private boolean upsert(String email, Set<Role> roles) {
        String normalised = AppUser.normalise(email);
        if (normalised.isEmpty() || !normalised.contains("@")) {
            return false;
        }
        var existing = users.findByEmail(normalised);
        if (existing.isPresent()) {
            // Already seeded in this same pass — an address in both env lists, or the admin who also
            // has comparisons. Merge rather than skip, so the wider grant wins.
            var user = existing.get();
            var merged = new LinkedHashSet<>(user.getRoles());
            merged.addAll(roles);
            user.setRoles(merged);
            users.save(user);
            return false;
        }
        users.save(new AppUser(normalised, null, roles, "system:seed"));
        return true;
    }
}
