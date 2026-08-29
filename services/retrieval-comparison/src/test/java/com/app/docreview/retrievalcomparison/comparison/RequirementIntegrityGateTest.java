package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.comparison.UnmappedStandardObligation;
import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.EvaluationLimitation;
import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.enums.ObligationType;
import com.app.docreview.contracts.enums.RequirementIntegrityStatus;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.comparison.application.CoverageLedgerService;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementIntegrity;
import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withholding a known-incomplete requirement from the comparison model. Architecture §12.7, §18.3.
 *
 * <p><strong>The failure this design replaced.</strong> A withdrawn revision let the comparison MODEL
 * report that our own extraction was incomplete. Because that branch was reachable on every requirement,
 * the model took it whenever a comparison was hard — for ordinary evidence uncertainty, for obligations
 * a clause already covered, and for reasoning it could not map onto the clause list. Measured across 191
 * requirements: evaluated fell from 187 to 46, then to 114 after a prompt recalibration. 58 responses
 * claimed the limitation while quoting nothing and failed validation twice.
 *
 * <p>Prompt wording was the wrong instrument, because the misfire rate multiplies across every
 * requirement and each misfire costs a whole control. The model cannot answer the question at all: it
 * sees one requirement and its retrieved passages, not the extractor, not the provenance spans withheld
 * from it, not the rest of the set.
 *
 * <p>So the judgement moved to ingestion — deterministic, governed, one reviewed record per affected
 * requirement, made before any model call. {@link Narrowness} pins the properties that keep it bounded.
 */
class RequirementIntegrityGateTest {

    private final CoverageLedgerService ledgerService = new CoverageLedgerService();

    private static final String DEADLINE = "within one business day";
    private static final String SPAN = "SPAN-43077829-166-0";

    private static UnmappedStandardObligation timingObligation() {
        return new UnmappedStandardObligation(DEADLINE, List.of(SPAN), ObligationType.TIMING,
                "Present in provenance, absent from the atomic clauses.");
    }

    private static RequirementIntegrity record(RequirementIntegrityStatus status,
                                               List<UnmappedStandardObligation> obligations) {
        return new RequirementIntegrity("id-1", "v-1", "REQSET-V3", "CHG-08-R2",
                "Emergency retrospective review within one business day",
                status, obligations, "SYSTEM_REVIEW_BATCH", "REQ-INTEGRITY-V1",
                "notes", Instant.parse("2026-08-29T00:00:00Z"));
    }

    private static StandardRequirementDocument requirement(String id) {
        var extracted = new ExtractedRequirement(id, null, null,
                "Emergency retrospective review within one business day",
                List.of(SPAN), NormativeStrength.MANDATORY,
                "The review must assess whether the emergency path was justified.",
                List.of(), null, List.of("record"), 0.9);
        return new StandardRequirementDocument("std-1", "v-1", "REQSET-V3", extracted,
                null, null, null);
    }

    private static Finding withheldFinding(String id) {
        return new Finding("f-" + id, "CMP-1", "v-1", "REQSET-V3", id, null, "Title",
                ComparisonStatus.INSUFFICIENT_EVIDENCE, null, null,
                List.of(SPAN), List.of(), List.of(), List.of(),
                "reason", null, 1.0, true, ApplicabilityStatus.APPLICABLE, null, null, null,
                EvaluationLimitation.STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE,
                List.of(timingObligation()), false);
    }

    @Nested
    @DisplayName("the comparison model is not asked to judge its own input")
    class ModelDoesNotSelfDiagnose {

        @Test
        @DisplayName("the model output record has no field for an extraction limitation")
        void outputRecordCannotCarryALimitation() {
            // THE structural guarantee, and the reason this cannot regress into the V3 failure. Prompt
            // wording is advisory; an absent field is not. Two prompt revisions failed to stop the
            // model claiming a limitation, so the fields were removed from the shape it fills in.
            var components = java.util.Arrays.stream(ComparisonModelOutput.class
                            .getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();

            assertThat(components)
                    .as("the model must not be able to report a limitation of our own extraction")
                    .doesNotContain("evaluationLimitation", "unmappedStandardObligations");
        }

        @Test
        @DisplayName("the prompt does not offer the extraction-limitation branch")
        void promptDoesNotOfferTheBranch() {
            var prompt = ComparisonPrompt.system("NONCE");

            assertThat(prompt)
                    .as("the withdrawn branch must be gone from the instructions")
                    .doesNotContain("STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE");
            assertThat(prompt)
                    .as("and so must the field it populated")
                    .doesNotContain("unmappedStandardObligations");
        }

        @Test
        @DisplayName("the promoted prompt and schema are V2")
        void promotedVersionsAreV2() {
            // §19.2: the version identifies the contract a stored comparison ran under. The shape IS
            // V2's again, so the version is V2 — a new number would name a shape that does not exist.
            assertThat(ComparisonPrompt.PROMPT_VERSION).isEqualTo("COMPARE-V2");
            assertThat(com.app.docreview.retrievalcomparison.comparison.validation
                    .ComparisonOutputValidator.SCHEMA_VERSION).isEqualTo("COMPARE-SCHEMA-V2");
        }

        @Test
        @DisplayName("the retry framework is retained for repairable failures")
        void retryIsRetained() {
            // Only the extraction-limitation branch was withdrawn. The targeted repair for a PARTIAL
            // that named no unsatisfied clause stays: that failure is deterministic, so re-asking the
            // identical question returns the identical answer.
            var repair = ComparisonPrompt.INCOMPLETE_PARTIAL_REPAIR;

            assertThat(repair).containsIgnoringCase("without naming an unsatisfied clause");
            assertThat(repair)
                    .as("it must offer only choices the model can verify from its own evidence")
                    .containsIgnoringCase("the status is ALIGNED, not PARTIAL");
            assertThat(repair)
                    .as("and must NOT reintroduce the withdrawn branch")
                    .doesNotContain("STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE");
        }
    }

    @Nested
    @DisplayName("the governed record is narrow")
    class Narrowness {

        @Test
        @DisplayName("a record with no obligation does not withhold the requirement")
        void unsupportedRecordDoesNotBlock() {
            // THE fail-safe direction. An unsupported claim about our own extraction must not withhold
            // a control — the reviewer would get a requirement excluded from the conclusion with
            // nothing to check. So the requirement is compared normally instead.
            //
            // This is the same rule the model-reported version failed, and the difference is which way
            // it fails: there an unsupported claim cost the whole requirement, here it costs nothing.
            assertThat(record(RequirementIntegrityStatus.INCOMPLETE_ATOMIC_COVERAGE, List.of())
                    .blocksComparison())
                    .isFalse();
            assertThat(record(RequirementIntegrityStatus.INCOMPLETE_ATOMIC_COVERAGE, null)
                    .blocksComparison())
                    .isFalse();
        }

        @Test
        @DisplayName("a COMPLETE record never withholds")
        void completeDoesNotBlock() {
            assertThat(record(RequirementIntegrityStatus.COMPLETE, List.of(timingObligation()))
                    .blocksComparison())
                    .isFalse();
        }

        @Test
        @DisplayName("an evidenced INCOMPLETE_ATOMIC_COVERAGE record withholds")
        void evidencedRecordBlocks() {
            assertThat(record(RequirementIntegrityStatus.INCOMPLETE_ATOMIC_COVERAGE,
                    List.of(timingObligation())).blocksComparison())
                    .isTrue();
        }

        @Test
        @DisplayName("comparisonEligible is a positive test on COMPLETE alone")
        void eligibilityIsAPositiveTest() {
            // So a status added later defaults to NOT comparison-eligible. Comparing a requirement
            // whose integrity is unknown yields a verdict indistinguishable from a trustworthy one.
            for (var status : RequirementIntegrityStatus.values()) {
                assertThat(status.comparisonEligible())
                        .as("%s", status)
                        .isEqualTo(status == RequirementIntegrityStatus.COMPLETE);
            }
        }
    }

    @Nested
    @DisplayName("requirement ids that recur across standards stay distinct")
    class RecurringIds {

        private static StandardRequirementDocument in(String standardVersionId, String id) {
            var extracted = new ExtractedRequirement(id, null, null, "Title",
                    List.of(SPAN), NormativeStrength.MANDATORY, "text",
                    List.of(), null, List.of(), 0.9);
            return new StandardRequirementDocument("std", standardVersionId, "REQSET-V3",
                    extracted, null, null, null);
        }

        private static Finding finding(String standardVersionId, String id,
                                       ComparisonStatus status,
                                       EvaluationLimitation limitation) {
            return new Finding("f-" + standardVersionId + "-" + id, "CMP-1", standardVersionId,
                    "REQSET-V3", id, null, "Title", status, null, null,
                    List.of(SPAN), List.of("p-1"), List.of(), List.of(),
                    "reason", null, 0.9, limitation != null,
                    ApplicabilityStatus.APPLICABLE, null, null, null,
                    limitation,
                    limitation == null ? List.of() : List.of(timingObligation()),
                    limitation == null);
        }

        @Test
        @DisplayName("the same id in two standards gets its own state from its own finding")
        void ledgerKeysOnStandardVersionToo() {
            // THE defect this pins, observed on CMP-f9b84cbb-d8b. Requirement ids recur across
            // standards — 47 of 191 in one three-standard comparison — because organisations derive
            // their standards from shared frameworks and keep the numbering.
            //
            // The ledger indexed findings by requirement id ALONE, so the first standard's finding won
            // and the rest were discarded. CHG-08-2 was withheld for an incomplete extraction in one
            // standard and ALIGNED in another; the ledger reported EVALUATED for both, silently
            // returning a requirement that was never assessed to the alignment conclusion.
            var ledger = ledgerService.build("CMP-1",
                    List.of(in("STD-A", "CHG-08-2"), in("STD-B", "CHG-08-2")),
                    List.of(
                            finding("STD-A", "CHG-08-2", ComparisonStatus.ALIGNED, null),
                            finding("STD-B", "CHG-08-2",
                                    ComparisonStatus.INSUFFICIENT_EVIDENCE,
                                    EvaluationLimitation
                                            .STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE)),
                    Set.of(), Map.of());

            assertThat(ledger.expectedCount()).isEqualTo(2);
            assertThat(ledger.byState())
                    .as("one evaluated, one withheld — not two evaluated")
                    .containsEntry(CoverageLedger.CoverageState.EVALUATED, 1L)
                    .containsEntry(CoverageLedger.CoverageState.EXTRACTION_REVIEW_REQUIRED, 1L);
            assertThat(ledger.conclusionEligibleCount())
                    .as("the withheld one must not reach the conclusion")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a finding from the wrong standard does not cover a requirement")
        void findingDoesNotCoverAnotherStandard() {
            // The other half of the same key bug: a requirement whose own standard produced no finding
            // must read as untested, even when a same-numbered requirement elsewhere was evaluated.
            // Keyed on the id alone this reported full coverage for a requirement never compared.
            var ledger = ledgerService.build("CMP-1",
                    List.of(in("STD-A", "CHG-08-2"), in("STD-B", "CHG-08-2")),
                    List.of(finding("STD-A", "CHG-08-2", ComparisonStatus.ALIGNED, null)),
                    Set.of(), Map.of());

            assertThat(ledger.untestedCount()).isEqualTo(1);
            assertThat(ledger.isComplete())
                    .as("the uncompared one is PENDING, which must block completion")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("a withheld requirement stays visible and out of the conclusion")
    class Visibility {

        @Test
        @DisplayName("it is EXTRACTION_REVIEW_REQUIRED in the ledger")
        void ledgerRecordsItsOwnState() {
            var ledger = ledgerService.build("CMP-1", List.of(requirement("CHG-08-R2")),
                    List.of(withheldFinding("CHG-08-R2")), Set.of(), Map.of());

            assertThat(ledger.byState())
                    .containsEntry(CoverageLedger.CoverageState.EXTRACTION_REVIEW_REQUIRED, 1L);
        }

        @Test
        @DisplayName("it counts as covered but not as conclusion-eligible")
        void coveredButNotConcludable() {
            // Not "not evaluated": that would send the reviewer to search their own document for an
            // obligation the system never asked about. Not conclusion-eligible either: a stated
            // obligation went unassessed, so no positive compliance statement may rest on it.
            var ledger = ledgerService.build("CMP-1", List.of(requirement("CHG-08-R2")),
                    List.of(withheldFinding("CHG-08-R2")), Set.of(), Map.of());

            assertThat(ledger.untestedCount()).isZero();
            assertThat(ledger.conclusionEligibleCount()).isZero();
            assertThat(ledger.extractionReviewRequiredCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("it does not block completion and is not retryable")
        void terminalAndNotRetryable() {
            var ledger = ledgerService.build("CMP-1", List.of(requirement("CHG-08-R2")),
                    List.of(withheldFinding("CHG-08-R2")), Set.of(), Map.of());

            assertThat(ledger.isComplete()).isTrue();
            assertThat(CoverageLedger.CoverageState.EXTRACTION_REVIEW_REQUIRED.retryable())
                    .as("no model call can supply a clause the extractor never produced")
                    .isFalse();
        }

        @Test
        @DisplayName("the finding cites the standard but claims nothing about the document")
        void citesStandardNotProcess() {
            // Nothing was compared, so citing process passages would imply a judgement that was never
            // made. The obligation is a fact about the STANDARD.
            var finding = withheldFinding("CHG-08-R2");

            assertThat(finding.standardSpanIds()).containsExactly(SPAN);
            assertThat(finding.processSpanIds()).isEmpty();
            assertThat(finding.extractionIncomplete()).isTrue();
            assertThat(finding.countsTowardConclusion()).isFalse();
            assertThat(finding.unmappedStandardObligations()).singleElement()
                    .satisfies(o -> assertThat(o.exactText()).isEqualTo(DEADLINE));
        }
    }
}
