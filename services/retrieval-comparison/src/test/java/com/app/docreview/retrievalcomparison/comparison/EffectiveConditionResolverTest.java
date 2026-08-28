package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.ConditionRoleSource;
import com.app.docreview.contracts.requirement.RequirementCondition;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.comparison.application.EffectiveConditionResolver;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Human review decisions overlay model extraction without mutating it. Architecture §12.5, §19.2.
 *
 * <p><strong>What this protects.</strong> A review of 115 model-predicted SCOPE_GATEs reclassified 113 of
 * them — 2% precision. So the model's own role classification cannot be permitted to remove a control
 * from a review, and a reviewer's correction has to reach the runtime decision without a re-extraction
 * that would discard the review itself.
 *
 * <p>The load-bearing property is that role and exclusion permission are SEPARATE. A test suite that only
 * checked roles would pass against an implementation where confirming "this is a scope gate" silently
 * granted the power to exclude — the exact conflation that turns one reviewer's classification into a
 * missing control on a report that looks clean.
 */
class EffectiveConditionResolverTest {

    private static final String SVID = "sv-change-mgmt-1";
    private static final String SET = "REQ-EXTRACT-V3";
    private static final String REQ = "req-0042";
    private static final String BATCH = "CONDROLE-REVIEW-V1";

    private final RequirementConditionOverrideRepository repository =
            mock(RequirementConditionOverrideRepository.class);
    private final EffectiveConditionResolver resolver = new EffectiveConditionResolver(repository);
    private final ApplicabilityResolver applicability = new ApplicabilityResolver();

    // --- fixtures -------------------------------------------------------------------------------

    /** A prose condition the model called a scope gate — the shape that was wrong 113 times out of 115. */
    private static RequirementCondition prose(String text, ConditionRole role) {
        return new RequirementCondition(text, role, List.of(), List.of("span-1"));
    }

    /** A condition carrying a machine-decidable documentTypes list. */
    private static RequirementCondition structured(String text, String... types) {
        return new RequirementCondition(text, ConditionRole.SCOPE_GATE, List.of(types),
                List.of("span-2"));
    }

    private static RequirementConditionOverride override(
            String conditionText, ConditionRole extracted, ConditionRole reviewed,
            ConditionReviewStatus status, boolean canExclude) {
        return new RequirementConditionOverride(
                SVID, SET, REQ, conditionText, extracted, "REQ-EXTRACT-V3",
                reviewed, status, canExclude, "reviewer rationale",
                "imsingh@stanford.edu", BATCH, "condrole-review-v1.csv", "deadbeef");
    }

    private void stubOverrides(RequirementConditionOverride... rows) {
        when(repository.findByStandardVersionIdAndRequirementSetVersionAndRequirementId(
                SVID, SET, REQ)).thenReturn(List.of(rows));
    }

    private List<EffectiveConditionResolver.EffectiveCondition> resolve(
            List<RequirementCondition> conditions) {
        return resolver.resolve(SVID, SET, REQ, conditions);
    }

    // --- precedence -----------------------------------------------------------------------------

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        @DisplayName("a confirmed override replaces the model's role")
        void confirmedOverrideWins() {
            stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, false));

            var result = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

            assertThat(result).singleElement().satisfies(e -> {
                assertThat(e.effectiveRole()).isEqualTo(ConditionRole.CONDITIONAL_OBLIGATION);
                assertThat(e.source()).isEqualTo(ConditionRoleSource.REVIEWER_OVERRIDE);
                assertThat(e.overrideBatchId()).isEqualTo(BATCH);
            });
        }

        @Test
        @DisplayName("the extracted role is preserved alongside the override, not overwritten")
        void extractionRemainsInspectable() {
            stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, false));

            var extracted = prose("Emergency changes", ConditionRole.SCOPE_GATE);
            var result = resolve(List.of(extracted));

            // The overlay must not rewrite history: what the model said stays readable next to what the
            // reviewer decided, which is what makes the extractor measurable later.
            assertThat(result.getFirst().condition().role()).isEqualTo(ConditionRole.SCOPE_GATE);
            assertThat(extracted.role()).isEqualTo(ConditionRole.SCOPE_GATE);
        }

        @Test
        @DisplayName("NEEDS_POLICY_OWNER resolves to UNKNOWN and is marked unresolved, not model-extracted")
        void needsPolicyOwnerIsUnresolved() {
            stubOverrides(override("When it is", ConditionRole.SCOPE_GATE,
                    ConditionRole.UNKNOWN, ConditionReviewStatus.NEEDS_POLICY_OWNER, false));

            var result = resolve(List.of(prose("When it is", ConditionRole.SCOPE_GATE)));

            assertThat(result).singleElement().satisfies(e -> {
                assertThat(e.effectiveRole()).isEqualTo(ConditionRole.UNKNOWN);
                // UNRESOLVED, not MODEL_EXTRACTED: a human looked and could not settle it. Collapsing
                // the two would hide an open question behind an unreviewed guess.
                assertThat(e.source()).isEqualTo(ConditionRoleSource.UNRESOLVED);
                assertThat(e.mayExclude()).isFalse();
            });
        }

        @Test
        @DisplayName("a REJECTED override does not fall back to the model's role")
        void rejectedDoesNotRestoreModelRole() {
            stubOverrides(override("Material changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.REJECTED, true));

            var result = resolve(List.of(prose("Material changes", ConditionRole.SCOPE_GATE)));

            assertThat(result.getFirst().effectiveRole()).isEqualTo(ConditionRole.UNKNOWN);
            assertThat(result.getFirst().mayExclude()).isFalse();
        }

        @Test
        @DisplayName("a RETIRED override is ignored entirely")
        void retiredIsIgnored() {
            stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.RETIRED, false));

            var result = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

            // A superseded decision must not shadow the current state; it falls through to extraction.
            assertThat(result.getFirst().source()).isEqualTo(ConditionRoleSource.MODEL_EXTRACTED);
            assertThat(result.getFirst().effectiveRole()).isEqualTo(ConditionRole.SCOPE_GATE);
        }

        @Test
        @DisplayName("when two active batches match, the most recently reviewed wins")
        void newestReviewWins() throws InterruptedException {
            var older = override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, false);
            // reviewedAt is stamped at construction, so a real gap is needed to order them.
            Thread.sleep(5);
            var newer = override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, false);
            // Deliberately stubbed newest-first, so passing cannot be an accident of input order.
            stubOverrides(newer, older);

            var result = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

            // Previously findFirst() made this arbitrary: a correction imported as a new batch could
            // be silently ignored depending on Mongo document order, showing a stale-but-plausible
            // role rather than an error.
            assertThat(result.getFirst().effectiveRole())
                    .isEqualTo(ConditionRole.CONDITIONAL_OBLIGATION);

            // And the same holds when the input arrives oldest-first.
            stubOverrides(older, newer);
            assertThat(resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)))
                    .getFirst().effectiveRole()).isEqualTo(ConditionRole.CONDITIONAL_OBLIGATION);
        }

        @Test
        @DisplayName("structured documentTypes outrank an unreviewed model role")
        void structuredMetadataBeatsModelRole() {
            stubOverrides();

            var result = resolve(List.of(structured("Limited to runbooks", "Runbook")));

            assertThat(result).singleElement().satisfies(e -> {
                assertThat(e.source()).isEqualTo(ConditionRoleSource.STRUCTURED_METADATA);
                // Machine-decidable on its own terms, so it does not depend on the model having named
                // the role correctly — and it may exclude.
                assertThat(e.mayExclude()).isTrue();
            });
        }

        @Test
        @DisplayName("with no override and no documentTypes the model's role stands, unable to exclude")
        void modelRoleIsLastResort() {
            stubOverrides();

            var result = resolve(List.of(prose("All production changes", ConditionRole.SCOPE_GATE)));

            assertThat(result).singleElement().satisfies(e -> {
                assertThat(e.effectiveRole()).isEqualTo(ConditionRole.SCOPE_GATE);
                assertThat(e.source()).isEqualTo(ConditionRoleSource.MODEL_EXTRACTED);
                assertThat(e.canDriveNotApplicable()).isFalse();
                assertThat(e.mayExclude()).isFalse();
            });
        }
    }

    // --- role vs permission ---------------------------------------------------------------------

    @Nested
    @DisplayName("exclusion permission is separate from the role")
    class RoleVersusPermission {

        @Test
        @DisplayName("a CONFIRMED scope gate cannot exclude unless exclusion is authorised")
        void confirmedGateWithoutAuthorisationCannotExclude() {
            stubOverrides(override("containing personal data", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, /* canExclude */ false));

            var result = resolve(List.of(structured("containing personal data", "Retention Policy")));

            // Confirming what the clause DOES is not the same as authorising it to remove a control.
            // This is the whole reason the two fields exist independently.
            assertThat(result.getFirst().effectiveRole()).isEqualTo(ConditionRole.SCOPE_GATE);
            assertThat(result.getFirst().mayExclude()).isFalse();
        }

        @Test
        @DisplayName("an authorised CONFIRMED scope gate may exclude")
        void authorisedConfirmedGateMayExclude() {
            stubOverrides(override("containing personal data", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, /* canExclude */ true));

            var result = resolve(List.of(structured("containing personal data", "Retention Policy")));

            assertThat(result.getFirst().mayExclude()).isTrue();
        }

        @Test
        @DisplayName("authorisation on a non-gate role still cannot exclude")
        void authorisationOnNonGateIsInert() {
            stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, true));

            var result = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

            // BOTH gates are required. Permission alone does not make a conditional obligation exclude.
            assertThat(result.getFirst().canDriveNotApplicable()).isTrue();
            assertThat(result.getFirst().mayExclude()).isFalse();
        }

        @Test
        @DisplayName("the document refuses to store exclusion permission on an unconfirmed review")
        void unconfirmedCannotCarryPermission() {
            var row = override("When it is", ConditionRole.SCOPE_GATE, ConditionRole.UNKNOWN,
                    ConditionReviewStatus.NEEDS_POLICY_OWNER, /* caller asks for */ true);

            // Enforced at construction as well as at resolution: an importer bug must not be able to
            // persist an exclusion authority that no confirmed review supports.
            assertThat(row.isCanDriveNotApplicable()).isFalse();
        }
    }

    // --- end-to-end through the applicability decision -------------------------------------------

    @Nested
    @DisplayName("effect on the applicability decision")
    class Downstream {

        private static final ApplicabilityResolver.ConfirmedContext PROCEDURE =
                new ApplicabilityResolver.ConfirmedContext("Procedure");

        @Test
        @DisplayName("an unreviewed prose scope gate is compared, never excluded")
        void unreviewedProseGateDoesNotExclude() {
            stubOverrides();
            var effective = resolve(List.of(prose("All production changes", ConditionRole.SCOPE_GATE)));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            // The 2%-precision finding, closed structurally: the model's own gate cannot exclude.
            assertThat(decision.status()).isNotEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(decision.shouldCompare()).isTrue();
            // APPLICABLE rather than UNCERTAIN, deliberately. An unauthorised prose gate carries no
            // documentTypes, so there is no stated scope to be uncertain ABOUT — and the review of these
            // 115 conditions found 113 were not scope gates at all. Flagging every one for human review
            // would manufacture doubt the review has already settled, and bury the two real gates.
            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(decision.needsScopeContext()).isFalse();
            // The text still reaches the comparison: "all production changes" describes work the document
            // should govern, so its absence is a gap worth reporting.
            assertThat(decision.conditions()).contains("All production changes");
        }

        @Test
        @DisplayName("a reviewer's reclassification to CONDITIONAL_OBLIGATION makes it plainly applicable")
        void reclassifiedConditionBecomesApplicable() {
            stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                    ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, false));
            var effective = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            // The condition is not discarded — it still informs the comparison, because a procedure that
            // omits emergency-change handling is a GAP rather than out of scope.
            assertThat(decision.conditions()).contains("Emergency changes");
        }

        @Test
        @DisplayName("a satisfied authorised gate still carries its text into the comparison")
        void satisfiedGateIsNotDropped() {
            stubOverrides(override("applies to procedures", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, true));
            var effective = resolve(List.of(structured("applies to procedures", "Procedure")));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            // A gate that matched is the one case where the condition legitimately gated and still did
            // not exclude. Losing its text here would hide WHY the requirement was held applicable.
            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(decision.conditions()).contains("applies to procedures");
        }

        @Test
        @DisplayName("an authorised gate excludes only on a genuine document-type conflict")
        void authorisedGateExcludesOnConflict() {
            stubOverrides(override("applies to retention schedules", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, true));
            var effective = resolve(
                    List.of(structured("applies to retention schedules", "Retention Schedule")));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(decision.reason()).contains("Retention Schedule").contains("Procedure");
            // Evidence is mandatory for an exclusion — a reviewer has to be able to check it.
            assertThat(decision.conditionSourceSpanIds()).isNotEmpty();
        }

        @Test
        @DisplayName("an authorised gate that matches the document type does not exclude")
        void authorisedGateMatchingTypeApplies() {
            stubOverrides(override("applies to procedures", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, true));
            var effective = resolve(List.of(structured("applies to procedures", "Procedure")));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("an authorised gate with unconfirmed document type is UNCERTAIN, and says what is missing")
        void unconfirmedContextDoesNotExclude() {
            stubOverrides(override("applies to retention schedules", ConditionRole.SCOPE_GATE,
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, true));
            var effective = resolve(
                    List.of(structured("applies to retention schedules", "Retention Schedule")));

            var decision = applicability.resolveGoverned(effective, new ApplicabilityResolver.ConfirmedContext(null));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.missingContextFields()).contains("documentType");
        }

        @Test
        @DisplayName("a co-occurring unresolved condition prevents a plain APPLICABLE")
        void unresolvedConditionSurfaces() {
            stubOverrides(
                    override("applies to procedures", ConditionRole.SCOPE_GATE,
                            ConditionRole.SCOPE_GATE, ConditionReviewStatus.CONFIRMED, true),
                    override("When it is", ConditionRole.SCOPE_GATE,
                            ConditionRole.UNKNOWN, ConditionReviewStatus.NEEDS_POLICY_OWNER, false));
            var effective = resolve(List.of(
                    structured("applies to procedures", "Procedure"),
                    prose("When it is", ConditionRole.SCOPE_GATE)));

            var decision = applicability.resolveGoverned(effective, PROCEDURE);

            // A satisfied gate must not swallow an open question sitting beside it — that would assert a
            // judgement nobody made.
            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.needsScopeContext()).isTrue();
        }

        @Test
        @DisplayName("no conditions is APPLICABLE with no override lookup at all")
        void unconditionalNeedsNoGovernance() {
            var effective = resolve(List.of());

            assertThat(effective).isEmpty();
            assertThat(applicability.resolveGoverned(effective, PROCEDURE).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }
    }

    // --- resilience -----------------------------------------------------------------------------

    @Test
    @DisplayName("an override store failure falls back to extracted roles rather than failing the comparison")
    void repositoryFailureDegradesSafely() {
        when(repository.findByStandardVersionIdAndRequirementSetVersionAndRequirementId(
                any(), any(), any())).thenThrow(new IllegalStateException("mongo down"));

        var result = resolve(List.of(prose("Emergency changes", ConditionRole.SCOPE_GATE)));

        assertThat(result).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo(ConditionRoleSource.MODEL_EXTRACTED);
            // The fallback is safe precisely because it cannot exclude: a comparison proceeds with one
            // extra requirement compared, not with a control silently missing.
            assertThat(e.mayExclude()).isFalse();
        });
    }

    @Test
    @DisplayName("override matching ignores case and surrounding whitespace but never partially matches")
    void matchingIsExactAfterNormalisation() {
        stubOverrides(override("Emergency changes", ConditionRole.SCOPE_GATE,
                ConditionRole.CONDITIONAL_OBLIGATION, ConditionReviewStatus.CONFIRMED, false));

        var result = resolve(List.of(
                prose("  emergency CHANGES  ", ConditionRole.SCOPE_GATE),
                prose("Emergency changes to production databases", ConditionRole.SCOPE_GATE)));

        assertThat(result.get(0).source()).isEqualTo(ConditionRoleSource.REVIEWER_OVERRIDE);
        // A prefix match would apply one reviewer's decision to a narrower condition they never read.
        assertThat(result.get(1).source()).isEqualTo(ConditionRoleSource.MODEL_EXTRACTED);
    }
}
