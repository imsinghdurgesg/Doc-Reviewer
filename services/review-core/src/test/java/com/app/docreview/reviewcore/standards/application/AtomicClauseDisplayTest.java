package com.app.docreview.reviewcore.standards.application;

import com.app.docreview.contracts.requirement.AtomicClause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readable rendering of an atomic clause. Architecture §12.5, §18.5.
 *
 * <p><strong>The defect this addresses.</strong> A clause is stored as structured fields with no
 * sentence form, so clause verdicts could only be labelled by ID. Requirement 5.1 ("change freeze
 * periods must be published AND observed") decomposes into two correct clauses, but both rendered as
 * bare IDs with the same status — two rows a reviewer could not tell apart. Clause-level results
 * exist to answer "which element failed", which an opaque suffix does not.
 */
class AtomicClauseDisplayTest {

    @Nested
    @DisplayName("sibling clauses become distinguishable")
    class Siblings {

        @Test
        @DisplayName("5.1's publish and observe clauses render differently")
        void publishVersusObserve() {
            // Verbatim from the extractor: subject and object are the same phrase, and condition
            // repeats timing. Both were reported MISSING / EVIDENCE_NOT_DOCUMENTED.
            var publish = new AtomicClause("5.1-a", "change freeze periods", "publish",
                    "change freeze periods", "during financial reporting close",
                    "during financial reporting close", false);
            var observe = new AtomicClause("5.1-b", "change freeze periods", "observe",
                    "change freeze periods", "during financial reporting close",
                    "during financial reporting close", false);

            assertThat(publish.displayText()).isNotEqualTo(observe.displayText());
            assertThat(publish.displayText()).contains("publish");
            assertThat(observe.displayText()).contains("observe");
        }

        @Test
        @DisplayName("4.3's log and retain clauses render differently")
        void logVersusRetain() {
            var log = new AtomicClause("4.3a", "organization", "log",
                    "all deployment activity", "", "", false);
            var retain = new AtomicClause("4.3b", "organization", "retain",
                    "logs of all deployment activity", "for a minimum of twelve months", "", false);

            assertThat(log.displayText()).isNotEqualTo(retain.displayText());
            // The retention period is the whole point of 4.3b and must survive into the label.
            assertThat(retain.displayText()).contains("twelve months");
        }
    }

    @Nested
    @DisplayName("rendering rules")
    class Rendering {

        @Test
        @DisplayName("an object identical to the subject is not repeated")
        void objectRestatingSubjectIsDropped() {
            var clause = new AtomicClause("5.1-a", "change freeze periods", "publish",
                    "change freeze periods", "", "", false);

            // "change freeze periods must publish change freeze periods" reads as a defect.
            assertThat(clause.displayText()).isEqualTo("change freeze periods must publish");
        }

        @Test
        @DisplayName("a condition that repeats the timing is not shown twice")
        void conditionRepeatingTimingIsDropped() {
            var clause = new AtomicClause("3.3.a", "organization", "document",
                    "rollback procedures", "for every production change",
                    "for every production change", false);

            assertThat(clause.displayText())
                    .isEqualTo("organization must document rollback procedures "
                            + "for every production change");
        }

        @Test
        @DisplayName("a distinct condition is shown in parentheses")
        void distinctConditionIsKept() {
            var clause = new AtomicClause("6.1-a", "third-party access", "be time-bound",
                    "", "", "when third-party access is granted", false);

            assertThat(clause.displayText())
                    .isEqualTo("third-party access must be time-bound "
                            + "(when third-party access is granted)");
        }

        @Test
        @DisplayName("a prohibition renders as must not")
        void negatedRendersAsProhibition() {
            // 2-2.2-2 is negated: losing the flag would invert the obligation.
            var clause = new AtomicClause("2-2.2-2", "an authorised approver", "implement",
                    "the change", "", "when approving a change", true);

            assertThat(clause.displayText()).startsWith("an authorised approver must not implement");
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("missing fields do not produce stray words or whitespace")
        void sparseClause() {
            var clause = new AtomicClause("X-1", null, "verify", null, null, null, false);

            // No subject means no "must" either — a bare "must verify" has no actor.
            assertThat(clause.displayText()).isEqualTo("verify");
        }

        @Test
        @DisplayName("an entirely empty clause renders empty rather than throwing")
        void emptyClause() {
            var clause = new AtomicClause("X-2", "", "", "", "", "", false);

            assertThat(clause.displayText()).isEmpty();
        }

        @Test
        @DisplayName("internal whitespace is collapsed")
        void whitespaceCollapsed() {
            var clause = new AtomicClause("X-3", "  organization  ", " log ",
                    "  activity ", "", "", false);

            assertThat(clause.displayText()).isEqualTo("organization must log activity");
        }
    }
}
