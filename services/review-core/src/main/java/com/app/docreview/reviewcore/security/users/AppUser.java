package com.app.docreview.reviewcore.security.users;

import com.app.docreview.reviewcore.security.Role;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Someone permitted to use the application, and what they may do. Architecture §33.
 *
 * <p><strong>Why this exists.</strong> Roles were resolved from two environment variables, so granting
 * someone admin meant editing VM metadata and restarting — with no record of who granted it or when.
 * For a tool whose output is meant to be defensible, "who authorised this person" should be answerable
 * from the application rather than from a shell history.
 *
 * <p><strong>Invite-only.</strong> Membership of {@code stanford.edu} authenticates a person; it does
 * not authorise them. A Google account proves identity, and this collection decides access — so the
 * ~100k accounts in the domain are not implicitly reviewers of whatever anyone uploads.
 *
 * <p>The email is the identity, lowercased. It is what Google verified and what the authorization
 * decision is made against, so it is the natural key — a separate id would allow two records for the
 * same person, and then two different answers about their roles.
 */
@Document(collection = "users")
@Getter
@NoArgsConstructor
public class AppUser {

    /** Whether this person may sign in at all, independent of what roles they hold. */
    public enum Status {
        ACTIVE,
        /**
         * Refused at sign-in AND on every subsequent request.
         *
         * <p>Checked per request, not only at login: a session lives for 30 minutes of inactivity, and
         * revoking access that stays effective for half an hour is not revoking access.
         */
        DISABLED
    }

    @Id
    private String id;

    /** Lowercased. Compared by exact equality — this is an authorization boundary, never a pattern. */
    @Indexed(unique = true)
    private String email;

    private String displayName;

    /**
     * What this person may do.
     *
     * <p>Always contains REVIEWER: a user who may sign in but do nothing has no reason to be listed,
     * and an empty set would silently produce a session that fails every authorization check with no
     * explanation the user could act on.
     */
    private Set<Role> roles;

    @Setter private Status status;

    private Instant createdAt;
    private String createdBy;
    @Setter private Instant updatedAt;
    @Setter private String updatedBy;

    /** Last successful sign-in, so an administrator can see which grants are actually in use. */
    @Setter private Instant lastLoginAt;

    public AppUser(String email, String displayName, Set<Role> roles, String createdBy) {
        this.email = normalise(email);
        this.displayName = displayName;
        this.roles = withReviewer(roles);
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
        this.updatedAt = this.createdAt;
        this.updatedBy = createdBy;
    }

    /** Lowercased and trimmed. Gmail-style addresses are case-insensitive; our comparison is not. */
    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void setRoles(Set<Role> roles) {
        this.roles = withReviewer(roles);
    }

    public void setDisplayName(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }

    private static Set<Role> withReviewer(Set<Role> roles) {
        var result = new LinkedHashSet<Role>();
        result.add(Role.REVIEWER);
        if (roles != null) {
            result.addAll(roles);
        }
        return result;
    }
}
