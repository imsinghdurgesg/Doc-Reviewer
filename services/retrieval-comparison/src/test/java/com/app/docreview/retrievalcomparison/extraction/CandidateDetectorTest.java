package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.extraction.CandidateCategory;
import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.retrievalcomparison.extraction.candidate.CandidateDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic candidate detection. Architecture §12.2, §12.3.
 *
 * <p>Every case here is drawn from real standard text, because the failures that matter are specific
 * phrasings rather than abstract categories.
 */
class CandidateDetectorTest {

    private final CandidateDetector detector = new CandidateDetector();

    private EvidenceSpan span(String id, String text, String... section) {
        return new EvidenceSpan(id, "STD-V1", 1, List.of(section), 1, 0, text.length(),
                text, "sha256:x", List.of(), "NODE-1", 1);
    }

    private CandidateCategory categoryOf(String text, String... section) {
        List<RequirementCandidate> out =
                detector.detect(List.of(span("S1", text, section)), s -> NodeType.PARAGRAPH);
        return out.isEmpty() ? null : out.get(0).category();
    }

    @Nested
    @DisplayName("prohibitions are never misread as obligations")
    class ProhibitionPrecedence {

        @Test
        void mustNotIsAProhibition() {
            // "must not" CONTAINS "must". Classifying this as an obligation inverts the requirement,
            // so a compliant process would be reported as violating and vice versa.
            assertThat(categoryOf(
                    "Emergency changes must not be closed without a retrospective review.",
                    "6 Emergency Changes"))
                    .isEqualTo(CandidateCategory.PROHIBITION);
        }

        @Test
        void noPersonMayIsAProhibition() {
            assertThat(categoryOf(
                    "No person may both approve and implement the same production change.",
                    "8 Segregation of Duties"))
                    .isEqualTo(CandidateCategory.PROHIBITION);
        }
    }

    @Nested
    @DisplayName("obligations without a modal verb are still detected")
    class NonModalObligations {

        @Test
        void retentionPeriodIsMandatory() {
            // No "must" anywhere. A modal-only detector misses the whole class of retention and
            // timing obligations, which are among the most commonly audited.
            assertThat(categoryOf("Approval evidence is retained for seven years.",
                    "4 Testing and Approval"))
                    .isEqualTo(CandidateCategory.MANDATORY_REQUIREMENT);
        }

        @Test
        void explicitMustIsMandatory() {
            assertThat(categoryOf(
                    "Every production change must be recorded in the change management system.",
                    "3 Change Record"))
                    .isEqualTo(CandidateCategory.MANDATORY_REQUIREMENT);
        }
    }

    @Test
    @DisplayName("a scoped obligation is conditional, not plain mandatory")
    void conditionalRequirement() {
        assertThat(categoryOf(
                "A documented rollback plan is required before deployment where the change "
                        + "affects production systems.", "5 Rollback"))
                .isEqualTo(CandidateCategory.CONDITIONAL_REQUIREMENT);
    }

    @Test
    @DisplayName("recommendations are advisory, so they are not reported as gaps")
    void advisoryGuidance() {
        assertThat(categoryOf("Teams should rehearse the rollback procedure in a lower environment.",
                "5 Rollback"))
                .isEqualTo(CandidateCategory.ADVISORY_GUIDANCE);
    }

    @Nested
    @DisplayName("non-normative content is excluded visibly, not silently")
    class NonNormative {

        @Test
        void definitionsAreNotControls() {
            // §12.8 rule 9 makes promoting a definition to a mandatory control a validation failure.
            assertThat(categoryOf(
                    "For the purposes of this standard, a production system means any system "
                            + "serving external customers.", "2 Definitions"))
                    .isEqualTo(CandidateCategory.DEFINITION);
        }

        @Test
        void signalFreeProseIsNotACandidate() {
            // Not worth a model call, and recorded as nothing rather than as a weak requirement.
            assertThat(categoryOf("The team meets on Tuesdays in the London office.",
                    "7 Governance"))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("grouping keeps a requirement with its qualifier")
    class Grouping {

        private List<RequirementCandidate> approvalSection() {
            return detector.detect(List.of(
                            span("S1", "Changes must be approved before deployment.", "4.2 Approval"),
                            span("S2", "The approver must be independent of the implementing engineer.",
                                    "4.2 Approval")),
                    s -> NodeType.PARAGRAPH);
        }

        @Test
        void sameSectionBecomesOneCandidate() {
            // §12.3's worked example. Sent separately, the second sentence loses what it qualifies
            // and the first loses the independence constraint — and no later validation can detect
            // that the resulting requirement is wrong.
            assertThat(approvalSection()).hasSize(1);
        }

        @Test
        void groupCarriesEverySourceSpan() {
            assertThat(approvalSection().get(0).spanIds()).containsExactly("S1", "S2");
        }

        @Test
        void groupTextRetainsTheQualifier() {
            assertThat(approvalSection().get(0).combinedText())
                    .contains("independent of the implementing engineer");
        }

        @Test
        void standardsOwnControlIdIsPreserved() {
            // §12.7: a reviewer who knows the standard navigates by 4.2, not a generated ID.
            assertThat(approvalSection().get(0).sourceControlId()).isEqualTo("4.2");
        }

        @Test
        void differentSectionsStaySeparate() {
            var out = detector.detect(List.of(
                            span("S1", "Changes must be approved.", "4 Approval"),
                            span("S2", "A rollback plan must exist.", "5 Rollback")),
                    s -> NodeType.PARAGRAPH);
            assertThat(out).hasSize(2);
        }
    }
}
