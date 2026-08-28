package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.retrievalcomparison.comparison.application.PolicyOwnerQueueService;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The policy-owner queue. Architecture §12.5, §17.6, §19.2.
 *
 * <p><strong>What the queue is for.</strong> A reviewer looked at a condition and could not settle
 * whether it limits which documents a standard governs. Recording a defensible reading as certain is the
 * failure being avoided, so the question stays open — and open questions that nobody can see are
 * indistinguishable from questions nobody had.
 *
 * <p>The properties worth protecting are that recurrences group into ONE decision, that extractor
 * self-contradiction is surfaced rather than silently resolved, and that nothing in the queue can
 * exclude a control.
 */
class PolicyOwnerQueueServiceTest {

    private final RequirementConditionOverrideRepository overrides =
            mock(RequirementConditionOverrideRepository.class);
    private final StandardRequirementRepository requirements =
            mock(StandardRequirementRepository.class);
    private final PolicyOwnerQueueService service =
            new PolicyOwnerQueueService(overrides, requirements);

    private static RequirementConditionOverride unresolved(
            String standardVersionId, String requirementId, String text, ConditionRole extracted) {
        return new RequirementConditionOverride(
                standardVersionId, "REQSET-V3", requirementId, text,
                extracted, "REQ-EXTRACT-V3",
                ConditionRole.UNKNOWN, ConditionReviewStatus.NEEDS_POLICY_OWNER,
                false, "could not settle from the text alone",
                "imsingh@stanford.edu", "CONDROLE-REVIEW-V1", "condrole-review-v1.csv", "abc123");
    }

    private void stub(RequirementConditionOverride... rows) {
        when(overrides.findByReviewStatus(ConditionReviewStatus.NEEDS_POLICY_OWNER))
                .thenReturn(List.of(rows));
        // No requirement text by default; the context-fetch cases stub it explicitly.
        when(requirements.findByStandardVersionIdAndRequirementSetVersion(any(), any()))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("grouping")
    class Grouping {

        @Test
        @DisplayName("the same phrase across versions becomes ONE question")
        void recurrencesCollapse() {
            stub(unresolved("sv-1", "CHG-14-2", "When it is", ConditionRole.SCOPE_GATE),
                    unresolved("sv-2", "CHG-14-2", "When it is", ConditionRole.SCOPE_GATE));

            var queue = service.openQuestions();

            // One decision, not two tickets. A per-row queue would present a single policy question as
            // several unrelated small ones and get it triaged as low priority twice.
            assertThat(queue).hasSize(1);
            assertThat(queue.getFirst().occurrences()).isEqualTo(2);
            assertThat(queue.getFirst().standardVersionIds()).containsExactly("sv-1", "sv-2");
        }

        @Test
        @DisplayName("grouping ignores case and surrounding whitespace")
        void groupingNormalises() {
            stub(unresolved("sv-1", "R1", "When it is", ConditionRole.SCOPE_GATE),
                    unresolved("sv-2", "R2", "  when IT is  ", ConditionRole.SCOPE_GATE));

            assertThat(service.openQuestions()).hasSize(1);
        }

        @Test
        @DisplayName("different phrases stay separate")
        void distinctPhrasesDoNotMerge() {
            stub(unresolved("sv-1", "R1", "When it is", ConditionRole.SCOPE_GATE),
                    unresolved("sv-1", "R2", "Material changes", ConditionRole.SCOPE_GATE));

            assertThat(service.openQuestions()).hasSize(2);
        }

        @Test
        @DisplayName("the most frequent question comes first")
        void orderedByOccurrences() {
            stub(unresolved("sv-1", "R1", "rare phrase", ConditionRole.SCOPE_GATE),
                    unresolved("sv-1", "R2", "common phrase", ConditionRole.SCOPE_GATE),
                    unresolved("sv-2", "R3", "common phrase", ConditionRole.SCOPE_GATE),
                    unresolved("sv-3", "R4", "common phrase", ConditionRole.SCOPE_GATE));

            var queue = service.openQuestions();

            // Ordering IS the triage. A phrase in three places deserves attention before one in a
            // single place, and the queue should not make a reader work that out.
            assertThat(queue.getFirst().conditionText()).isEqualTo("common phrase");
            assertThat(queue.getFirst().occurrences()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("signals a policy owner needs")
    class Signals {

        @Test
        @DisplayName("extractor self-contradiction is reported, not resolved")
        void inconsistentExtractionIsSurfaced() {
            stub(unresolved("sv-1", "CHG-14-2", "When it is", ConditionRole.SCOPE_GATE),
                    unresolved("sv-2", "CHG-14-2", "When it is",
                            ConditionRole.CONDITIONAL_OBLIGATION));

            var q = service.openQuestions().getFirst();

            assertThat(q.inconsistentlyExtracted()).isTrue();
            // BOTH roles kept. Silently picking the majority would hide that the extractor is
            // unreliable here — which is the single most useful fact for whoever has to rule.
            assertThat(q.extractedRoles())
                    .containsExactlyInAnyOrder("SCOPE_GATE", "CONDITIONAL_OBLIGATION");
        }

        @Test
        @DisplayName("a consistently extracted phrase is not flagged as inconsistent")
        void consistentExtractionIsNotFlagged() {
            stub(unresolved("sv-1", "R1", "Material changes", ConditionRole.SCOPE_GATE),
                    unresolved("sv-2", "R2", "Material changes", ConditionRole.SCOPE_GATE));

            assertThat(service.openQuestions().getFirst().inconsistentlyExtracted()).isFalse();
        }

        @Test
        @DisplayName("a short fragment is flagged as possibly truncated")
        void truncationIsFlagged() {
            stub(unresolved("sv-1", "CHG-14-2", "When it is", ConditionRole.SCOPE_GATE));

            // Points at a different remedy: repair extraction rather than rule on words the standard
            // never wrote that way. The real clause is "When the change is a material security or
            // privacy change" — unjudgeable once truncated to three words.
            assertThat(service.openQuestions().getFirst().truncated()).isTrue();
        }

        @Test
        @DisplayName("a full-length condition is not flagged as truncated")
        void fullConditionIsNotFlagged() {
            stub(unresolved("sv-1", "R1",
                    "only to restore a material service or contain an active security threat",
                    ConditionRole.EXCEPTION_OR_WAIVER));

            assertThat(service.openQuestions().getFirst().truncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("safety")
    class Safety {

        @Test
        @DisplayName("nothing in the queue can drive NOT_APPLICABLE")
        void queuedQuestionsCannotExclude() {
            // Enforced at construction: an unconfirmed override cannot carry exclusion authority
            // whatever a caller asks for. Asserted here too, because the queue is exactly the set of
            // conditions where a mistaken exclusion would be least defensible.
            var row = new RequirementConditionOverride(
                    "sv-1", "REQSET-V3", "CHG-14-2", "When it is",
                    ConditionRole.SCOPE_GATE, "REQ-EXTRACT-V3",
                    ConditionRole.SCOPE_GATE, ConditionReviewStatus.NEEDS_POLICY_OWNER,
                    /* caller asks for exclusion */ true, "unsettled",
                    "imsingh@stanford.edu", "B1", "f.csv", "sha");

            assertThat(row.isCanDriveNotApplicable()).isFalse();
            assertThat(row.effectiveRole()).isEqualTo(ConditionRole.UNKNOWN);
        }

        @Test
        @DisplayName("an empty queue is a success state, not an error")
        void emptyQueueIsFine() {
            stub();
            assertThat(service.openQuestions()).isEmpty();
        }

        @Test
        @DisplayName("a store failure yields an empty queue rather than an exception")
        void storeFailureDegradesSafely() {
            when(overrides.findByReviewStatus(any()))
                    .thenThrow(new IllegalStateException("mongo down"));

            // Nothing is blocked by this queue — every condition in it is already being compared under
            // uncertainty — so an outage must not break the page around it.
            assertThat(service.openQuestions()).isEmpty();
        }
    }
}
