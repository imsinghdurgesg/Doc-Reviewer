package com.app.docreview.reviewcore.security.users;

import com.app.docreview.reviewcore.security.Role;
import com.app.docreview.reviewcore.shared.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may sign in, and the ways an administrator must not be able to lock everyone out.
 * Architecture §33.
 *
 * <p>The operations here are trivial — set a role, set a status. What is worth testing is the
 * refusals, because their failure mode is an application nobody can administer, recoverable only by
 * editing the database directly.
 */
class AppUserServiceTest {

    private AppUserRepository users;
    private AppUserService service;

    private static final String ME = "admin@stanford.edu";
    private static final String OTHER = "other@stanford.edu";

    @BeforeEach
    void setUp() {
        users = Mockito.mock(AppUserRepository.class);
        service = new AppUserService(users);
        Mockito.when(users.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
    }

    private AppUser stored(String email, Set<Role> roles, AppUser.Status status) {
        var user = new AppUser(email, "Someone", roles, "test");
        user.setStatus(status);
        Mockito.when(users.findByEmail(email)).thenReturn(Optional.of(user));
        return user;
    }

    private void activeAdmins(long count) {
        Mockito.when(users.countByRolesContainingAndStatus(Role.ADMIN, AppUser.Status.ACTIVE))
                .thenReturn(count);
    }

    @Nested
    @DisplayName("sign-in")
    class SignIn {

        @Test
        @DisplayName("an unlisted account is refused")
        void unknownRefused() {
            // Invite-only: a Stanford account proves identity, not authorisation. ~100k accounts share
            // the domain.
            Mockito.when(users.findByEmail("stranger@stanford.edu")).thenReturn(Optional.empty());

            assertThat(service.authorise("stranger@stanford.edu", "Stranger")).isEmpty();
        }

        @Test
        @DisplayName("a disabled account is refused")
        void disabledRefused() {
            stored(OTHER, Set.of(Role.REVIEWER), AppUser.Status.DISABLED);

            assertThat(service.authorise(OTHER, "Other")).isEmpty();
        }

        @Test
        @DisplayName("an active account returns its stored roles")
        void activeAllowed() {
            stored(OTHER, Set.of(Role.STANDARDS_ADMIN), AppUser.Status.ACTIVE);

            var authorised = service.authorise(OTHER, "Other");

            assertThat(authorised).isPresent();
            // REVIEWER is always present: a user who may sign in but do nothing has no reason to be
            // listed, and an empty set fails every check with nothing the user could act on.
            assertThat(authorised.get().getRoles())
                    .containsExactlyInAnyOrder(Role.REVIEWER, Role.STANDARDS_ADMIN);
        }

        @Test
        @DisplayName("the address is matched case-insensitively")
        void emailNormalised() {
            stored(OTHER, Set.of(Role.REVIEWER), AppUser.Status.ACTIVE);

            assertThat(service.authorise("OTHER@Stanford.EDU", "Other")).isPresent();
        }
    }

    @Nested
    @DisplayName("lockout guards")
    class LockoutGuards {

        @Test
        @DisplayName("you cannot remove your own admin role")
        void noSelfDemotion() {
            stored(ME, Set.of(Role.ADMIN), AppUser.Status.ACTIVE);
            activeAdmins(5);

            assertThatThrownBy(() -> service.updateRoles(ME, Set.of(Role.REVIEWER), ME))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("another administrator");
        }

        @Test
        @DisplayName("you cannot disable your own account")
        void noSelfDisable() {
            stored(ME, Set.of(Role.ADMIN), AppUser.Status.ACTIVE);
            activeAdmins(5);

            assertThatThrownBy(() -> service.setStatus(ME, AppUser.Status.DISABLED, ME))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("the last admin cannot be demoted")
        void noRemovingLastAdmin() {
            // The mistake a careful person still makes: demoting someone ELSE who happens to be the
            // only remaining administrator. It does not look like self-harm, and the result is the
            // same.
            stored(OTHER, Set.of(Role.ADMIN), AppUser.Status.ACTIVE);
            activeAdmins(1);

            assertThatThrownBy(() -> service.updateRoles(OTHER, Set.of(Role.REVIEWER), ME))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("last administrator");
        }

        @Test
        @DisplayName("the last admin cannot be disabled")
        void noDisablingLastAdmin() {
            stored(OTHER, Set.of(Role.ADMIN), AppUser.Status.ACTIVE);
            activeAdmins(1);

            assertThatThrownBy(() -> service.setStatus(OTHER, AppUser.Status.DISABLED, ME))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("last administrator");
        }

        @Test
        @DisplayName("demoting one of several admins is allowed")
        void demotionAllowedWhenOthersRemain() {
            // The guards must not make ordinary administration impossible.
            stored(OTHER, Set.of(Role.ADMIN), AppUser.Status.ACTIVE);
            activeAdmins(3);

            var updated = service.updateRoles(OTHER, Set.of(Role.REVIEWER), ME);

            assertThat(updated.getRoles()).containsExactly(Role.REVIEWER);
        }

        @Test
        @DisplayName("disabling a non-admin is never blocked by the admin count")
        void disablingReviewerUnaffected() {
            // A reviewer is not an administrator, so the last-admin count is irrelevant — checking it
            // anyway would refuse a legitimate action whenever exactly one admin existed.
            stored(OTHER, Set.of(Role.REVIEWER), AppUser.Status.ACTIVE);
            activeAdmins(1);

            var updated = service.setStatus(OTHER, AppUser.Status.DISABLED, ME);

            assertThat(updated.getStatus()).isEqualTo(AppUser.Status.DISABLED);
        }
    }

    @Nested
    @DisplayName("invite")
    class Invite {

        @Test
        @DisplayName("rejects a duplicate rather than silently overwriting roles")
        void duplicateRejected() {
            Mockito.when(users.existsByEmail(OTHER)).thenReturn(true);

            assertThatThrownBy(() -> service.invite(OTHER, Set.of(Role.ADMIN), ME))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already on the list");
        }

        @Test
        @DisplayName("rejects a value that is not an address")
        void invalidEmailRejected() {
            assertThatThrownBy(() -> service.invite("not-an-email", Set.of(Role.REVIEWER), ME))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.invite("  ", Set.of(Role.REVIEWER), ME))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("stores the address lowercased")
        void storedNormalised() {
            Mockito.when(users.existsByEmail("new@stanford.edu")).thenReturn(false);

            var invited = service.invite("New@Stanford.EDU", Set.of(Role.REVIEWER), ME);

            assertThat(invited.getEmail()).isEqualTo("new@stanford.edu");
        }
    }
}
