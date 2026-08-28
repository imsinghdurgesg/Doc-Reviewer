package com.app.docreview.reviewcore.security.sso;

import com.app.docreview.reviewcore.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain restriction and role assignment. Architecture §33.
 *
 * <p>Two decisions, both of which fail silently in the dangerous direction. An unenforced hosted
 * domain admits every Google account on the internet while the login page still looks
 * organization-restricted; an over-broad allowlist hands out the ability to change the standards a
 * review is measured against.
 */
class SsoPropertiesTest {

    private static SsoProperties properties(String admins, String standards) {
        return new SsoProperties("test-client-id", "stanford.edu", admins, standards);
    }

    @Nested
    @DisplayName("hosted domain")
    class HostedDomain {

        @Test
        @DisplayName("accepts the configured domain")
        void acceptsConfiguredDomain() {
            assertThat(properties(null, null).requireHostedDomain("stanford.edu")).isTrue();
        }

        @Test
        @DisplayName("rejects any other domain")
        void rejectsOtherDomains() {
            var props = properties(null, null);
            assertThat(props.requireHostedDomain("gmail.com")).isFalse();
            // A lookalike. Suffix matching would accept this, which is why the comparison is exact.
            assertThat(props.requireHostedDomain("notstanford.edu")).isFalse();
            assertThat(props.requireHostedDomain("stanford.edu.evil.com")).isFalse();
        }

        @Test
        @DisplayName("rejects an absent claim")
        void rejectsAbsentClaim() {
            // A consumer Google account has no `hd` claim at all. Treating absence as acceptable is
            // exactly the hole this check exists to close.
            assertThat(properties(null, null).requireHostedDomain(null)).isFalse();
            assertThat(properties(null, null).requireHostedDomain("")).isFalse();
        }

        @Test
        @DisplayName("is case- and whitespace-insensitive")
        void toleratesCasing() {
            // Google normalises the claim, but the configured value is hand-typed.
            assertThat(properties(null, null).requireHostedDomain("Stanford.EDU")).isTrue();
            assertThat(properties(null, null).requireHostedDomain(" stanford.edu ")).isTrue();
        }

        @Test
        @DisplayName("a blank client id fails startup")
        void blankClientIdRefusesToStart() {
            // Without an audience to check against, a token minted for another application verifies
            // against Google's keys and names a real stanford.edu user — so it would be accepted.
            assertThatThrownBy(() -> new SsoProperties("", "stanford.edu", null, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GOOGLE_CLIENT_ID");
        }

        @Test
        @DisplayName("a blank configured domain fails startup")
        void blankDomainRefusesToStart() {
            // Running with an unrestricted login is worse than not starting, because nothing signals
            // that it happened.
            assertThatThrownBy(() -> new SsoProperties("cid", "", null, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SSO_HOSTED_DOMAIN");

            assertThatThrownBy(() -> new SsoProperties("cid", null, null, null).validate())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("role assignment")
    class Roles {

        @Test
        @DisplayName("every authenticated user is a reviewer")
        void authenticatedUsersAreReviewers() {
            assertThat(properties(null, null).rolesFor("someone@stanford.edu"))
                    .containsExactly(Role.REVIEWER);
        }

        @Test
        @DisplayName("an allowlisted admin also gets standards-admin")
        void adminImpliesStandardsAdmin() {
            // Listed once, not twice. An administrator who could not manage standards would need a
            // second entry, and forgetting it produces a confusing partial grant.
            var roles = properties("boss@stanford.edu", null).rolesFor("boss@stanford.edu");

            assertThat(roles).containsExactlyInAnyOrder(
                    Role.REVIEWER, Role.ADMIN, Role.STANDARDS_ADMIN);
        }

        @Test
        @DisplayName("a standards admin does not become a full admin")
        void standardsAdminIsNotAdmin() {
            var roles = properties(null, "curator@stanford.edu").rolesFor("curator@stanford.edu");

            assertThat(roles).containsExactlyInAnyOrder(Role.REVIEWER, Role.STANDARDS_ADMIN);
            assertThat(roles).doesNotContain(Role.ADMIN);
        }

        @Test
        @DisplayName("matching is case-insensitive and tolerates spacing in the list")
        void matchingIsForgivingAboutFormat() {
            var props = properties(" Boss@Stanford.edu , other@stanford.edu ", null);

            assertThat(props.rolesFor("boss@stanford.edu")).contains(Role.ADMIN);
            assertThat(props.rolesFor("BOSS@STANFORD.EDU")).contains(Role.ADMIN);
            assertThat(props.rolesFor("other@stanford.edu")).contains(Role.ADMIN);
        }

        @Test
        @DisplayName("a non-listed address gets no administrative role")
        void nonListedGetsNoPrivilege() {
            var props = properties("boss@stanford.edu", "curator@stanford.edu");

            assertThat(props.rolesFor("someone@stanford.edu")).containsExactly(Role.REVIEWER);
            // Substring matching would grant this; the comparison is on the whole entry.
            assertThat(props.rolesFor("notboss@stanford.edu")).containsExactly(Role.REVIEWER);
            assertThat(props.rolesFor("boss@stanford.edu.evil.com")).containsExactly(Role.REVIEWER);
        }

        @Test
        @DisplayName("an empty allowlist grants nothing beyond reviewer")
        void emptyAllowlistGrantsNothing() {
            // The default configuration. A blank list must not be read as "everyone".
            assertThat(properties("", "").rolesFor("someone@stanford.edu"))
                    .containsExactly(Role.REVIEWER);
            assertThat(properties(null, null).rolesFor("someone@stanford.edu"))
                    .containsExactly(Role.REVIEWER);
        }

        @Test
        @DisplayName("a missing email gets no administrative role")
        void missingEmailGetsNothing() {
            var props = properties("boss@stanford.edu", null);

            assertThat(props.rolesFor(null)).containsExactly(Role.REVIEWER);
            assertThat(props.rolesFor("")).containsExactly(Role.REVIEWER);
        }
    }
}
