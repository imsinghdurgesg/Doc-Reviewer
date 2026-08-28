package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Organizational SSO settings. Architecture §33.
 *
 * <p>Read only under {@code docreview.security.mode=sso}. Demo mode is unchanged and remains the
 * default, so nothing here affects a local run.
 *
 * @param hostedDomain      the Google Workspace domain users must belong to, e.g. {@code
 *                          stanford.edu}. Enforced on the ID TOKEN, not merely requested — see
 *                          {@link #requireHostedDomain(String)}
 * @param adminEmails       addresses granted {@link Role#ADMIN}
 * @param standardsEmails   addresses granted {@link Role#STANDARDS_ADMIN}
 */
@ConfigurationProperties(prefix = "docreview.security.sso")
public record SsoProperties(
        String clientId,
        String hostedDomain,
        String adminEmails,
        String standardsEmails
) {

    /**
     * Fails fast on an unset hosted domain.
     *
     * <p>Without it every Google account on the internet is a valid login. An empty value is a
     * configuration mistake with no safe interpretation, so it stops startup rather than quietly
     * opening the application to the world.
     */
    public void validate() {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(
                    "SSO mode requires GOOGLE_CLIENT_ID to be set. Without it an ID token's audience "
                            + "cannot be checked, and a token issued for another application would "
                            + "verify against Google's keys and be accepted.");
        }
        if (hostedDomain == null || hostedDomain.isBlank()) {
            throw new IllegalStateException(
                    "SSO mode requires SSO_HOSTED_DOMAIN to be set (e.g. stanford.edu). Refusing to "
                            + "start with an unrestricted login.");
        }
    }

    /**
     * Whether an ID token's {@code hd} claim satisfies the configured domain.
     *
     * <p><strong>This is the check that actually restricts access.</strong> Passing {@code hd} on
     * the authorization request is a UI hint — it preselects the account chooser — and an attacker
     * driving the flow directly simply omits it. Validating only the request parameter is a common
     * and quiet hole: the login page looks Stanford-restricted while any Google account works.
     *
     * <p>Compared case-insensitively because Google normalises the claim but the configured value is
     * hand-typed.
     */
    public boolean requireHostedDomain(String claim) {
        return claim != null
                && claim.trim().equalsIgnoreCase(hostedDomain.trim());
    }

    /**
     * Roles for an authenticated email address.
     *
     * <p>Every authenticated user is a {@link Role#REVIEWER}: they authenticated against the
     * organization's directory, and reviewing is the read-mostly capability the tool exists to
     * provide. The two administrative roles are additive and allowlisted, because they can change
     * the standards a review is measured against (§33).
     *
     * <p>{@code ADMIN} implies {@code STANDARDS_ADMIN}, matching the demo seeding: an administrator
     * who could not manage standards would have to be listed twice, and forgetting the second entry
     * produces a confusing partial grant.
     */
    public Set<Role> rolesFor(String email) {
        Set<Role> roles = new LinkedHashSet<>();
        roles.add(Role.REVIEWER);

        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            return roles;
        }
        if (contains(adminEmails, normalised)) {
            roles.add(Role.ADMIN);
            roles.add(Role.STANDARDS_ADMIN);
        }
        if (contains(standardsEmails, normalised)) {
            roles.add(Role.STANDARDS_ADMIN);
        }
        return roles;
    }

    /** Comma-separated, whitespace-tolerant, case-insensitive membership. */
    private static boolean contains(String csv, String normalisedEmail) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        return Arrays.stream(csv.split(","))
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty())
                .anyMatch(entry -> entry.equals(normalisedEmail));
    }
}
