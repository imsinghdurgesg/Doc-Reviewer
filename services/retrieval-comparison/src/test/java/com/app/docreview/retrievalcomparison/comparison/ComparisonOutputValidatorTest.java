package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import com.app.docreview.retrievalcomparison.comparison.validation.ComparisonOutputValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparison output validation. Architecture §18.1–18.3.
 *
 * <p>These checks are what stand between a model response and a finding a reviewer acts on.
 */
class ComparisonOutputValidatorTest {

    private final ComparisonOutputValidator validator = new ComparisonOutputValidator();

    private static final Set<String> STANDARD_SPANS = Set.of("SPAN-STD-1");
    private static final Set<String> PROCESS_SPANS = Set.of("SPAN-PROC-1", "SPAN-PROC-2");
    private static final Set<String> CLAUSES = Set.of("C-A", "C-B");

    private SearchCoverage goodCoverage() {
        return new SearchCoverage(List.of("4 Approval"), 120, 0.25, 0.31,
                List.of(), List.of(), true, true);
    }

    private SearchCoverage badCoverage() {
        return new SearchCoverage(List.of("4 Approval"), 120, 0.25, 0.31,
                List.of(), List.of("Security Procedure SEC-7"), true, true);
    }

    private ComparisonModelOutput output(ComparisonStatus status, GapType gapType,
                                         List<String> standardSpans, List<String> processSpans,
                                         List<String> satisfied, List<String> unsatisfied) {
        return new ComparisonModelOutput("CHG-04", status, gapType, satisfied, unsatisfied,
                standardSpans, processSpans, "a reason", null, 0.9, false);
    }

    private List<String> validate(ComparisonModelOutput out, SearchCoverage coverage) {
        return validator.validate(out, "CHG-04", STANDARD_SPANS, PROCESS_SPANS,
                Set.of("SPAN-STD-1"), coverage, CLAUSES);
    }

    @Test
    @DisplayName("a well-formed ALIGNED verdict passes")
    void acceptsValidAligned() {
        assertThat(validate(output(ComparisonStatus.ALIGNED, null,
                List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), List.of("C-A", "C-B"), List.of()),
                goodCoverage())).isEmpty();
    }

    @Nested
    @DisplayName("evidence ownership: fabrication is impossible")
    class EvidenceOwnership {

        @Test
        void rejectsUnsuppliedProcessSpan() {
            // The model may emit any string; one that was never offered cannot become evidence.
            assertThat(validate(output(ComparisonStatus.ALIGNED, null,
                    List.of("SPAN-STD-1"), List.of("SPAN-INVENTED"), List.of("C-A"), List.of()),
                    goodCoverage()))
                    .anyMatch(e -> e.contains("SPAN-INVENTED"));
        }

        @Test
        void rejectsMissingRequirementSourceSpan() {
            // Citing a neighbouring clause while claiming to apply this one produces a finding that
            // looks supported and is not.
            assertThat(validate(output(ComparisonStatus.ALIGNED, null,
                    List.of(), List.of("SPAN-PROC-1"), List.of("C-A"), List.of()),
                    goodCoverage()))
                    .anyMatch(e -> e.contains("omits the requirement's own source span"));
        }

        @Test
        void rejectsUnknownClauseId() {
            assertThat(validate(output(ComparisonStatus.ALIGNED, null,
                    List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), List.of("C-INVENTED"), List.of()),
                    goodCoverage()))
                    .anyMatch(e -> e.contains("C-INVENTED"));
        }
    }

    @Nested
    @DisplayName("status/evidence consistency")
    class StatusConsistency {

        @Test
        void alignedWithoutProcessEvidenceIsRejected() {
            // The worst unsupported verdict available: "compliant" with nothing to point at.
            assertThat(validate(output(ComparisonStatus.ALIGNED, null,
                    List.of("SPAN-STD-1"), List.of(), List.of("C-A"), List.of()), goodCoverage()))
                    .anyMatch(e -> e.contains("ALIGNED requires supporting process evidence"));
        }

        @Test
        void contradictedWithoutProcessEvidenceIsRejected() {
            // A contradiction is a claim about what the document says, so it must quote it.
            assertThat(validate(output(ComparisonStatus.CONTRADICTED, null,
                    List.of("SPAN-STD-1"), List.of(), List.of(), List.of("C-A")), goodCoverage()))
                    .anyMatch(e -> e.contains("CONTRADICTED requires explicit conflicting"));
        }

        @Test
        void partialWithoutUnsatisfiedClauseIsRejected() {
            // Otherwise "partial" tells a reviewer nothing about what to fix.
            assertThat(validate(output(ComparisonStatus.PARTIAL, GapType.WEAKER,
                    List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), List.of("C-A"), List.of()),
                    goodCoverage()))
                    .anyMatch(e -> e.contains("PARTIAL requires at least one unsatisfied clause"));
        }

        @Test
        void missingOverIncompleteCoverageIsRejected() {
            // The most damaging error this system can make: reporting a search failure as a gap.
            // Here an unresolved referenced procedure means the requirement may live in a document
            // that was never supplied.
            assertThat(validate(output(ComparisonStatus.MISSING, null,
                    List.of("SPAN-STD-1"), List.of(), List.of(), List.of("C-A")), badCoverage()))
                    .anyMatch(e -> e.contains("use INSUFFICIENT_EVIDENCE"));
        }

        @Test
        void missingWithGoodCoverageIsAccepted() {
            // Absence legitimately has no process evidence — when the search was adequate.
            assertThat(validate(output(ComparisonStatus.MISSING, null,
                    List.of("SPAN-STD-1"), List.of(), List.of(), List.of("C-A")), goodCoverage()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("schema-level checks")
    class SchemaChecks {

        @Test
        void rejectsRequirementIdMismatch() {
            // A mismatch means a provider mix-up or a successful injection; either way unusable.
            var out = new ComparisonModelOutput("OTHER-99", ComparisonStatus.ALIGNED, null,
                    List.of("C-A"), List.of(), List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"),
                    "reason", null, 0.9, false);
            assertThat(validate(out, goodCoverage()))
                    .anyMatch(e -> e.contains("requirementId mismatch"));
        }

        @Test
        void toleratesGapTypeOnNonPartialStatus() {
            // A gapType on MISSING is noise — it reads as "merely weaker" — but it does not make the
            // STATUS wrong, and §17.2 only says gap types "may include" those values for PARTIAL.
            //
            // Previously rejected, which discarded correct MISSING verdicts: one real run lost two
            // requirements this way, and §18.4 then reported them as unevaluated — which a reviewer
            // reads as an unchecked control. RequirementComparisonService drops the field when
            // building the Finding, so the noise never reaches the UI either way.
            assertThat(validate(output(ComparisonStatus.MISSING, GapType.WEAKER,
                    List.of("SPAN-STD-1"), List.of(), List.of(), List.of("C-A")), goodCoverage()))
                    .noneMatch(e -> e.contains("only valid for PARTIAL"));
        }

        @Test
        void acceptsAnnotatedClauseReferences() {
            // Models echo the clause ID with its description: "C-A: who=reviewer" rather than "C-A".
            // The reference is correct and only its formatting is not, so it must not be treated as
            // an invented clause.
            var out = new ComparisonModelOutput("CHG-04", ComparisonStatus.ALIGNED, null,
                    List.of("C-A: who=independent reviewer"), List.of(),
                    List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), "reason", null, 0.9, false);
            assertThat(validate(out, goodCoverage()))
                    .noneMatch(e -> e.contains("is not part of this requirement"));
        }

        @Test
        void acceptsDotSuffixedClauseReferences() {
            // The real failure: a requirement declaring one clause "C-A" lost four validation errors
            // to "C-A.who", "C-A.does", "C-A.to", "C-A.when" — the model decomposed the obligation
            // and prefixed each part with the correct ID. §18.4 then counted the whole requirement
            // as unevaluated, which reads as an unchecked control.
            //
            // Cannot be fixed by splitting on '.': real clause IDs contain dots (3.3-a, 4.1-c1), so
            // the separator is indistinguishable from the ID's own punctuation.
            var out = new ComparisonModelOutput("CHG-04", ComparisonStatus.ALIGNED, null,
                    List.of("C-A.who", "C-A.does", "C-A.to"), List.of(),
                    List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), "reason", null, 0.9, false);
            assertThat(validate(out, goodCoverage()))
                    .noneMatch(e -> e.contains("is not part of this requirement"));
        }

        @Test
        void stillRejectsFabricatedClauses() {
            // The guard must survive the normalisation above: a clause the requirement does not
            // contain means the model invented an element, and §18.5 would aggregate over fiction.
            var out = new ComparisonModelOutput("CHG-04", ComparisonStatus.ALIGNED, null,
                    List.of("C-INVENTED"), List.of(),
                    List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"), "reason", null, 0.9, false);
            assertThat(validate(out, goodCoverage()))
                    .anyMatch(e -> e.contains("is not part of this requirement"));
        }

        @Test
        void rejectsOutOfRangeConfidence() {
            var out = new ComparisonModelOutput("CHG-04", ComparisonStatus.ALIGNED, null,
                    List.of("C-A"), List.of(), List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"),
                    "reason", null, 1.4, false);
            assertThat(validate(out, goodCoverage()))
                    .anyMatch(e -> e.contains("outside 0.0-1.0"));
        }

        @Test
        void rejectsEmptyReason() {
            // A verdict without a reason cannot be reviewed, only obeyed.
            var out = new ComparisonModelOutput("CHG-04", ComparisonStatus.ALIGNED, null,
                    List.of("C-A"), List.of(), List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"),
                    "  ", null, 0.9, false);
            assertThat(validate(out, goodCoverage()))
                    .anyMatch(e -> e.contains("unreviewable"));
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("requirement id matching")
    class RequirementIdMatching {

        /** Same as the shared helper, but with a model-supplied id we control. */
        private List<String> validateWithId(String modelId) {
            var out = new ComparisonModelOutput(modelId, ComparisonStatus.ALIGNED, null,
                    List.of("C-A", "C-B"), List.of(), List.of("SPAN-STD-1"), List.of("SPAN-PROC-1"),
                    "a reason", null, 0.9, false);
            return validator.validate(out, "CHG-04", STANDARD_SPANS, PROCESS_SPANS,
                    Set.of("SPAN-STD-1"), goodCoverage(), CLAUSES);
        }

        @Test
        @DisplayName("accepts the id echoed with its label")
        void acceptsLabelledId() {
            // The real failure: a strict equals() rejected "REQUIREMENT 1-1" for "1-1", so one
            // correct evaluation per run was discarded and the control reported as unevaluated.
            assertThat(validateWithId("REQUIREMENT CHG-04")).isEmpty();
            assertThat(validateWithId("Requirement: CHG-04")).isEmpty();
            assertThat(validateWithId("Control CHG-04")).isEmpty();
            assertThat(validateWithId("clause chg-04")).isEmpty();
            assertThat(validateWithId("  CHG-04.  ")).isEmpty();
        }

        @Test
        @DisplayName("still rejects an answer about a DIFFERENT requirement")
        void rejectsDifferentId() {
            // What the guard is actually for: a provider mix-up or a successful injection.
            // Normalisation must not weaken this.
            assertThat(validateWithId("CHG-05"))
                    .anyMatch(p -> p.contains("requirementId mismatch"));
            assertThat(validateWithId("REQUIREMENT CHG-05"))
                    .anyMatch(p -> p.contains("requirementId mismatch"));
            assertThat(validateWithId("CHG-040"))
                    .anyMatch(p -> p.contains("requirementId mismatch"));
        }

        @Test
        @DisplayName("reports the model's own rendering in the error, not the normalised form")
        void errorNamesWhatTheModelSaid() {
            // An operator debugging this needs the actual string, or they cannot tell which of the
            // two ids was wrong.
            assertThat(validateWithId("REQUIREMENT CHG-99"))
                    .anyMatch(p -> p.contains("REQUIREMENT CHG-99"));
        }
    }
}
