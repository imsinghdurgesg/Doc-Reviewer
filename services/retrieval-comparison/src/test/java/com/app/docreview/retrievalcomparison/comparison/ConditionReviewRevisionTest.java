package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revising a condition review decision. Architecture §12.5.
 *
 * <p><strong>Why this needs its own suite.</strong> Recording these decisions used to require a reviewed
 * CSV, so the only way to change one was to import another file. The Admin UI can now revise a single
 * condition in place, which adds a state transition the CSV path never had: a decision that already
 * authorises exclusion being reclassified to something that must not.
 *
 * <p>Exclusion is the one action that removes a control from a review, so every test here is about
 * whether that authority is granted or withdrawn correctly.
 */
class ConditionReviewRevisionTest {

    private static RequirementConditionOverride authorisedScopeGate() {
        return new RequirementConditionOverride(
                "v-1", "REQSET-V3", "CHG-01-2", "For cloud-hosted systems",
                ConditionRole.CONDITIONAL_OBLIGATION, "REQ-EXTRACT-V3",
                ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED,
                true, "Confirmed with the policy owner",
                "alice", "CONDROLE-REVIEW-V1",
                "condrole-review-v1.csv", "abc123");
    }

    @Nested
    @DisplayName("exclusion authority is recomputed, never carried over")
    class ExclusionRecomputed {

        @Test
        @DisplayName("reclassifying away from SCOPE_GATE withdraws exclusion in the same edit")
        void reclassifyingWithdrawsExclusion() {
            // THE transition this suite exists for. A condition confirmed as a scope gate and
            // authorised to exclude, later judged to be merely a conditional obligation, must lose that
            // authority immediately. Carrying it forward would keep a control excluded from reviews on
            // the strength of a classification that no longer exists — and nothing on screen would
            // show that the reason had evaporated.
            var override = authorisedScopeGate();
            assertThat(override.isCanDriveNotApplicable()).isTrue();

            override.revise(ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED,
                    // Even with the caller still asking for it.
                    true, "On review this only says when the duty fires", "bob", "UI-bob-2026-08-31");

            assertThat(override.isCanDriveNotApplicable())
                    .as("only a scope gate may exclude")
                    .isFalse();
            assertThat(override.getReviewedRole()).isEqualTo(ConditionRole.CONDITIONAL_OBLIGATION);
        }

        @Test
        @DisplayName("an unconfirmed decision cannot authorise exclusion")
        void unconfirmedCannotExclude() {
            // Both guards are independent. A reviewer who could not decide has produced information —
            // that the question is open — and an open question must not remove a control.
            var override = authorisedScopeGate();

            override.revise(ConditionRole.UNKNOWN, ConditionReviewStatus.NEEDS_POLICY_OWNER,
                    true, "Escalated: scope wording is ambiguous", "bob", "UI-bob-2026-08-31");

            assertThat(override.isCanDriveNotApplicable()).isFalse();
        }

        @Test
        @DisplayName("a confirmed scope gate without the opt-in still cannot exclude")
        void confirmingIsNotAuthorising() {
            // Confirming WHAT a clause does is not the same as authorising it to remove a control. A
            // reviewer may be certain it is a scope gate and still want a person to decide whether
            // acting on it is appropriate.
            var override = authorisedScopeGate();

            override.revise(ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED,
                    false, "Withdrawing exclusion authority pending sign-off",
                    "bob", "UI-bob-2026-08-31");

            assertThat(override.getReviewedRole()).isEqualTo(ConditionRole.SCOPE_GATE);
            assertThat(override.isCanDriveNotApplicable())
                    .as("the opt-in is necessary as well as the role")
                    .isFalse();
        }

        @Test
        @DisplayName("a confirmed scope gate WITH the opt-in may exclude")
        void bothConditionsGrantIt() {
            var override = new RequirementConditionOverride(
                    "v-1", "REQSET-V3", "CHG-01-2", "For cloud-hosted systems",
                    ConditionRole.CONDITIONAL_OBLIGATION, "REQ-EXTRACT-V3",
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED,
                    false, "initial", "alice", "CONDROLE-REVIEW-V1", "f.csv", "abc");

            override.revise(ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED,
                    true, "Standard covers cloud systems only", "bob", "UI-bob-2026-08-31");

            assertThat(override.isCanDriveNotApplicable()).isTrue();
        }
    }

    @Nested
    @DisplayName("provenance after a revision")
    class Provenance {

        @Test
        @DisplayName("the file name and hash are CLEARED, not left pointing at a stale file")
        void fileProvenanceIsCleared() {
            // The stored row named condrole-review-v1.csv and its hash, so a reader could prove which
            // file produced the decision. Revising in the UI makes that pairing false — the row would
            // still name a file it no longer matches, inviting a verification that will disagree.
            //
            // A stale reference is a worse lie than an absent one, so it goes.
            var override = authorisedScopeGate();
            assertThat(override.getSourceFileName()).isEqualTo("condrole-review-v1.csv");

            override.revise(ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED,
                    true, "Re-confirmed after discussion", "bob", "UI-bob-2026-08-31");

            assertThat(override.getSourceFileName()).isNull();
            assertThat(override.getSourceSha256()).isNull();
        }

        @Test
        @DisplayName("actor, batch and timestamp are restamped")
        void actorIsRestamped() {
            // An amended decision carrying the original author's name would misattribute the change,
            // which matters most for the field that can remove a control from a review.
            var override = authorisedScopeGate();
            var before = override.getReviewedAt();

            override.revise(ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED,
                    true, "Re-confirmed", "bob", "UI-bob-2026-08-31");

            assertThat(override.getReviewedBy()).isEqualTo("bob");
            assertThat(override.getReviewBatchId()).isEqualTo("UI-bob-2026-08-31");
            assertThat(override.getReviewedAt()).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("the EXTRACTED role survives a revision")
        void extractedRoleSurvives() {
            // The disagreement between what the model said and what a person decided is the measurement
            // that says whether extraction is improving. Overwriting the extracted role during review
            // would erase it and make every classification look like an agreement.
            var override = authorisedScopeGate();

            override.revise(ConditionRole.TEMPORAL_OR_SEQUENCE, ConditionReviewStatus.CONFIRMED,
                    false, "Actually a deadline", "bob", "UI-bob-2026-08-31");

            assertThat(override.getExtractedRole())
                    .isEqualTo(ConditionRole.CONDITIONAL_OBLIGATION);
            assertThat(override.getReviewedRole()).isEqualTo(ConditionRole.TEMPORAL_OR_SEQUENCE);
        }
    }
}
