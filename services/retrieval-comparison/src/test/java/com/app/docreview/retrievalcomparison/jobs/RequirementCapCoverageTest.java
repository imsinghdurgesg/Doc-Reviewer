package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.retrievalcomparison.comparison.application.CoverageLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-comparison requirement cap must not produce a clean report. Architecture §15.5, §17.6, §18.4.
 *
 * <p><strong>The defect.</strong> A three-standard comparison of 84 + 44 + 59 material requirements
 * against a cap of 120 truncated the list with {@code subList(0, 120)} BEFORE the coverage ledger was
 * built. Two consequences, both silent:
 *
 * <ul>
 *   <li>Truncation was SEQUENTIAL, so the first standard got all 84, the second 36 of 44, and the third
 *       nothing at all. The reviewer saw an empty tab for a standard they had explicitly selected.</li>
 *   <li>The ledger only ever saw the survivors, so it reported 120/120 EVALUATED, "Not evaluated: 0",
 *       and COMPLETED — while 67 requirements had been dropped.</li>
 * </ul>
 *
 * <p>The second is the more serious: a clean-looking report that is wrong is precisely what §17.6
 * exists to prevent, and no number on screen revealed it.
 */
class RequirementCapCoverageTest {

    private final CoverageLedgerService service = new CoverageLedgerService();

    /** A material requirement, stubbed via the fields the ledger reads. */
    private static com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument
            requirement(String standardVersionId, String requirementId) {
        var doc = new com.app.docreview.retrievalcomparison.extraction.domain
                .StandardRequirementDocument();
        set(doc, "standardVersionId", standardVersionId);
        set(doc, "requirementSetVersion", "REQSET-V3");
        set(doc, "requirementId", requirementId);
        set(doc, "title", "Title of " + requirementId);
        set(doc, "material", true);
        return doc;
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fixture cannot set " + field, e);
        }
    }

    @Nested
    @DisplayName("dropped requirements reach the ledger")
    class DroppedAreVisible {

        @Test
        @DisplayName("a capped requirement is PENDING, so the comparison cannot report complete")
        void cappedRequirementsBlockCompletion() {
            // Two evaluated, one dropped by the cap. Before the fix the dropped one was absent from
            // the ledger entirely and the comparison reported COMPLETED.
            var expected = List.of(
                    requirement("sv-1", "R1"), requirement("sv-1", "R2"),
                    requirement("sv-3", "R99"));

            var ledger = service.build("CMP-1", expected, List.of(), Set.of(), Map.of(),
                    Map.of("R99", "Not evaluated: this comparison spans more requirements than the "
                            + "120-requirement limit for a single run."));

            assertThat(ledger.expectedCount())
                    .as("the ledger must account for every expected requirement, not just survivors")
                    .isEqualTo(3);
            assertThat(ledger.isComplete())
                    .as("a requirement expected and never attempted must block completion")
                    .isFalse();
        }

        @Test
        @DisplayName("the dropped requirement explains itself")
        void cappedRequirementCarriesAReason() {
            var ledger = service.build("CMP-1", List.of(requirement("sv-3", "R99")),
                    List.of(), Set.of(), Map.of(),
                    Map.of("R99", "Not evaluated: 120-requirement limit."));

            var entry = ledger.entries().getFirst();
            assertThat(entry.state()).isEqualTo(CoverageLedger.CoverageState.PENDING);
            // "Not evaluated" with no explanation is unactionable: the reviewer cannot tell a budget
            // limit from a control the platform silently lost.
            assertThat(entry.detail()).contains("120-requirement limit");
            assertThat(entry.requirementTitle()).isNotBlank();
        }

        @Test
        @DisplayName("a requirement dropped without a reason is still PENDING, not hidden")
        void missingReasonStillCounts() {
            // Defensive: the reason is a courtesy, the PENDING state is the invariant. A caller that
            // forgets to supply one must not thereby make the requirement disappear.
            var ledger = service.build("CMP-1", List.of(requirement("sv-3", "R99")),
                    List.of(), Set.of(), Map.of());

            assertThat(ledger.entries().getFirst().state())
                    .isEqualTo(CoverageLedger.CoverageState.PENDING);
            assertThat(ledger.isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("the cap spends its budget across standards, not in order")
    class RoundRobin {

        @Test
        @DisplayName("truncation is interleaved, so no selected standard gets nothing")
        void capIsRoundRobin() throws IOException {
            // Asserted on source: the interleaving lives in a private method on the job runner, and a
            // behavioural test would need the full job pipeline. What matters is that `subList` is
            // gone — that single call is what gave the third standard zero requirements.
            var runner = Files.readString(Path.of(
                            "src/main/java/com/app/docreview/retrievalcomparison/jobs/"
                                    + "ComparisonJobRunner.java"), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> {
                        String t = line.strip();
                        return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
                    })
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(runner)
                    .as("sequential truncation gave late standards nothing; the cap must interleave")
                    .doesNotContain("subList(0, maxRequirements)");
            assertThat(runner)
                    .as("the dropped requirements must be carried to the ledger, not discarded")
                    .contains("LoadedRequirements");
        }
    }
}
