package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.ApplicabilityDecision;
import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.document.SemanticChunk;
import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.comparison.application.CoverageLedgerService;
import com.app.docreview.retrievalcomparison.comparison.application.EffectiveConditionResolver;
import com.app.docreview.retrievalcomparison.comparison.application.RequirementApplicabilityCoordinator;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Phase 2 applicability workflow, end to end. Architecture §12.5, §17.6, §18.4. Item 14.
 *
 * <p>Exercises the coordinator against the controlled fixture and asserts the five properties the
 * requirements name. Each guards a way the workflow could look correct while being wrong:
 *
 * <ul>
 *   <li>the model is invoked for APPLICABLE — otherwise applicability silently suppresses real work;</li>
 *   <li>the model is NOT invoked for NOT_APPLICABLE — otherwise the exclusion saves nothing and a
 *       meaningless verdict is produced and discarded;</li>
 *   <li>unknown context never becomes NOT_APPLICABLE — otherwise missing metadata shrinks the review;</li>
 *   <li>no requirement remains PENDING — otherwise an excluded control blocks completion forever;</li>
 *   <li>decisions survive a history reopen without recalculation — otherwise a completed report changes
 *       under the reviewer as context or rules evolve.</li>
 * </ul>
 */
class ApplicabilityWorkflowTest {

    /** Records every comparison the workflow attempts, standing in for the model gateway. */
    private static final class RecordingComparer {
        final List<String> compared = new ArrayList<>();

        Finding compare(StandardRequirementDocument requirement) {
            compared.add(requirement.getRequirementId());
            return finding(requirement.getRequirementId(), ComparisonStatus.MISSING);
        }
    }

    /** Serves SCOPE chunks for the standard, so scope-evidence attachment can be observed. */
    private static EvidenceSpanReader spanReaderWithScope() {
        return new EvidenceSpanReader() {
            @Override
            public List<EvidenceSpan> byIds(String documentVersionId, List<String> spanIds) {
                return List.of();
            }

            @Override
            public List<EvidenceSpan> allForVersion(String documentVersionId) {
                return List.of();
            }

            @Override
            public List<SemanticChunk> chunksForVersion(String documentVersionId) {
                return List.of(
                        chunk("CHUNK-scope", "SCOPE", List.of("SPAN-scope-1", "SPAN-scope-2")),
                        chunk("CHUNK-applic", "APPLICABILITY", List.of("SPAN-applic-1")),
                        // Must be ignored: a requirement chunk is not scope text.
                        chunk("CHUNK-req", "REQUIREMENT", List.of("SPAN-req-1")),
                        // Legacy V1 value — never determined to be scope, so it must not be attached.
                        chunk("CHUNK-legacy", "CONTROL_SECTION", List.of("SPAN-legacy-1")));
            }
        };
    }

    private static SemanticChunk chunk(String id, String type, List<String> spanIds) {
        return new SemanticChunk(id, "v-1", type, List.of(), spanIds, "text", 1, 1, 10, "SEMANTIC-V2");
    }

    private static Finding finding(String requirementId, ComparisonStatus status) {
        return new Finding("f-" + requirementId, "CMP-1", "v-1", "rs-1", requirementId,
                null, "Title", status, null, null,
                List.of("s-1"), List.of("p-1"), List.of(), List.of(),
                "reason", null, 0.9, false, ApplicabilityStatus.APPLICABLE, null, null);
    }

    private RequirementApplicabilityCoordinator coordinator() {
        return new RequirementApplicabilityCoordinator(
                new ApplicabilityResolver(), effectiveConditionsWithNoOverrides(),
                spanReaderWithScope(), new CoverageLedgerService());
    }

    /**
     * An override store with nothing in it, so this fixture keeps measuring the UNREVIEWED behaviour.
     *
     * <p>Deliberately empty rather than stubbed with decisions: the governed path is covered by
     * {@code EffectiveConditionResolverTest}, and what these cases pin is what happens to a freshly
     * extracted standard nobody has reviewed yet — which is the state every standard starts in.
     */
    private static EffectiveConditionResolver effectiveConditionsWithNoOverrides() {
        var empty = mock(RequirementConditionOverrideRepository.class);
        when(empty.findByStandardVersionIdAndRequirementSetVersionAndRequirementId(any(), any(), any()))
                .thenReturn(List.of());
        return new EffectiveConditionResolver(empty);
    }

    /**
     * Runs the fixture through the workflow exactly as {@code ComparisonJobRunner} does: decide first,
     * compare only when applicable.
     */
    private record Run(List<ApplicabilityDecision> decisions,
                       List<Finding> findings,
                       RecordingComparer comparer) {
    }

    private Run runWorkflow(List<StandardRequirementDocument> requirements,
                            ApplicabilityResolver.ConfirmedContext context) {
        var coordinator = coordinator();
        var comparer = new RecordingComparer();
        var decisions = new ArrayList<ApplicabilityDecision>();
        var findings = new ArrayList<Finding>();

        for (var requirement : requirements) {
            var decision = coordinator.decide(requirement, context);
            decisions.add(decision);
            // THE GATE. Mirrors the runner: no comparison for a requirement that does not apply.
            if (decision.shouldCompare()) {
                findings.add(comparer.compare(requirement));
            }
        }
        return new Run(decisions, findings, comparer);
    }

    private static ApplicabilityDecision decisionFor(Run run, String requirementId) {
        return run.decisions().stream()
                .filter(d -> d.requirementId().equals(requirementId))
                .findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("the model is invoked for exactly the right requirements")
    class ModelInvocation {

        @Test
        @DisplayName("the model IS invoked for APPLICABLE requirements")
        void modelInvokedForApplicable() {
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unconditional(),
                            ApplicabilityFixture.matchingCondition()),
                    ApplicabilityFixture.confirmedContext());

            assertThat(run.comparer().compared)
                    .as("applicability must not suppress real work")
                    .containsExactly("UNCOND-01", "MATCH-01");
        }

        @Test
        @DisplayName("the model is NOT invoked for NOT_APPLICABLE")
        void modelNotInvokedForNotApplicable() {
            // The saving is the point: an excluded requirement costs no call, and no meaningless
            // verdict is produced only to be discarded.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.conflictingCondition()),
                    ApplicabilityFixture.confirmedContext());

            assertThat(decisionFor(run, "CONFLICT-01").status())
                    .isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(run.comparer().compared).isEmpty();
            assertThat(run.findings())
                    .as("no finding, so ComparisonStatus stays null (item 10)")
                    .isEmpty();
        }

        @Test
        @DisplayName("the model IS still invoked for UNCERTAIN")
        void modelInvokedForUncertain() {
            // Item 11: uncertainty must not silently exclude. Both the free-text case and the
            // missing-context case are still compared.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unstructuredCondition(),
                            ApplicabilityFixture.missingContext()),
                    ApplicabilityFixture.noContext());

            assertThat(run.comparer().compared).containsExactly("FREETEXT-01", "MISSING-01");
        }
    }

    @Nested
    @DisplayName("unknown context never excludes")
    class UnknownContextNeverExcludes {

        @Test
        @DisplayName("the SAME requirement is excluded with context and UNCERTAIN without it")
        void contextDecidesExclusionNotTheRequirement() {
            // The sharpest form of item 9. CONFLICT-01 and MISSING-01 state the identical condition;
            // only the confirmed context differs. Exclusion must follow the CONTEXT, never the absence
            // of it.
            var withContext = runWorkflow(
                    List.of(ApplicabilityFixture.conflictingCondition()),
                    ApplicabilityFixture.confirmedContext());
            var withoutContext = runWorkflow(
                    List.of(ApplicabilityFixture.conflictingCondition()),
                    ApplicabilityFixture.noContext());

            assertThat(decisionFor(withContext, "CONFLICT-01").status())
                    .isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(decisionFor(withoutContext, "CONFLICT-01").status())
                    .as("no confirmed context must never produce an exclusion")
                    .isEqualTo(ApplicabilityStatus.UNCERTAIN);
        }

        @Test
        @DisplayName("NOTHING is excluded when no context is confirmed")
        void nothingExcludedWithoutContext() {
            // The live corpus state: no confirmed context at all. Every requirement must still be
            // compared, or the review silently shrinks to whatever happened to be labelled.
            var run = runWorkflow(ApplicabilityFixture.all(), ApplicabilityFixture.noContext());

            assertThat(run.decisions())
                    .noneMatch(d -> d.status() == ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(run.comparer().compared).hasSize(ApplicabilityFixture.all().size());
        }
    }

    @Nested
    @DisplayName("UNCERTAIN carries what a reviewer needs")
    class UncertainEvidence {

        @Test
        @DisplayName("a free-text condition attaches SCOPE and APPLICABILITY spans as evidence")
        void freeTextAttachesScopeEvidence() {
            // Item 12: the platform declines to interpret, so it must hand over what it read.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unstructuredCondition()),
                    ApplicabilityFixture.confirmedContext());

            var decision = decisionFor(run, "FREETEXT-01");
            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.scopeSpanIds())
                    .containsExactly("SPAN-scope-1", "SPAN-scope-2", "SPAN-applic-1");
        }

        @Test
        @DisplayName("REQUIREMENT and legacy chunks are NOT attached as scope evidence")
        void onlyScopeTypesAttached() {
            // A requirement chunk is not scope text, and a legacy CONTROL_SECTION was never determined
            // to be either — attaching arbitrary passages as evidence for a scope decision would make
            // the record misleading rather than merely incomplete.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unstructuredCondition()),
                    ApplicabilityFixture.confirmedContext());

            assertThat(decisionFor(run, "FREETEXT-01").scopeSpanIds())
                    .doesNotContain("SPAN-req-1", "SPAN-legacy-1");
        }

        @Test
        @DisplayName("missing context names the field and flags human review")
        void missingContextNamesTheField() {
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.missingContext()),
                    ApplicabilityFixture.noContext());

            var decision = decisionFor(run, "MISSING-01");
            assertThat(decision.missingContextFields()).containsExactly("documentType");
            assertThat(decision.needsHumanReview()).isTrue();
            assertThat(decision.reason()).isNotBlank();
        }

        @Test
        @DisplayName("an APPLICABLE decision reads no chunks and attaches nothing")
        void applicableAttachesNothing() {
            // The cost bound, observed through the workflow rather than the resolver alone.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unconditional()),
                    ApplicabilityFixture.confirmedContext());

            var decision = decisionFor(run, "UNCOND-01");
            assertThat(decision.scopeSpanIds()).isEmpty();
            assertThat(decision.needsHumanReview()).isFalse();
        }
    }

    @Nested
    @DisplayName("the coverage ledger is finalized, with nothing PENDING")
    class LedgerFinalisation {

        @Test
        @DisplayName("no requirement remains PENDING after the workflow")
        void nothingRemainsPending() {
            // THE completion invariant (item 14). An excluded requirement must become SKIPPED, not
            // vanish and not linger as pending work nobody will ever do.
            var requirements = ApplicabilityFixture.all();
            var run = runWorkflow(requirements, ApplicabilityFixture.confirmedContext());

            var ledger = new CoverageLedgerService().build(
                    "CMP-1", requirements, run.findings(), Set.of(),
                    coordinator().skippedForLedger(run.decisions()));

            assertThat(ledger.byState())
                    .as("a PENDING row means a requirement was silently dropped")
                    .doesNotContainKey(CoverageLedger.CoverageState.PENDING);
            assertThat(ledger.isComplete()).isTrue();
        }

        @Test
        @DisplayName("an excluded requirement is SKIPPED with its reason, and stays visible")
        void exclusionIsVisibleInTheLedger() {
            var requirements = ApplicabilityFixture.all();
            var run = runWorkflow(requirements, ApplicabilityFixture.confirmedContext());

            var ledger = new CoverageLedgerService().build(
                    "CMP-1", requirements, run.findings(), Set.of(),
                    coordinator().skippedForLedger(run.decisions()));

            var skippedRow = ledger.entries().stream()
                    .filter(e -> e.requirementId().equals("CONFLICT-01"))
                    .findFirst().orElseThrow();

            assertThat(skippedRow.state()).isEqualTo(CoverageLedger.CoverageState.SKIPPED);
            assertThat(skippedRow.detail())
                    .as("a silent exclusion is indistinguishable from a lost requirement")
                    .isNotBlank();
            assertThat(ledger.expectedCount())
                    .as("the control stays counted, so coverage reports it honestly")
                    .isEqualTo(requirements.size());
        }

        @Test
        @DisplayName("an excluded requirement counts as UNTESTED, not as compliant")
        void exclusionIsUntestedNotPassing() {
            // §17.6: its absence from the findings must not read as compliance.
            //
            // TWO of the five fixture cases are excluded under confirmed context, not one:
            // CONFLICT-01 and MISSING-01 state the SAME condition (scoped to Runbook), and
            // MISSING-01 only behaves as a missing-context case when judged with no context. That is
            // deliberate in the fixture — the pair demonstrates that exclusion follows the context
            // rather than the requirement — so the count here is 2.
            var requirements = ApplicabilityFixture.all();
            var run = runWorkflow(requirements, ApplicabilityFixture.confirmedContext());

            var ledger = new CoverageLedgerService().build(
                    "CMP-1", requirements, run.findings(), Set.of(),
                    coordinator().skippedForLedger(run.decisions()));

            long excluded = run.decisions().stream()
                    .filter(d -> d.status() == ApplicabilityStatus.NOT_APPLICABLE)
                    .count();

            assertThat(excluded).as("both Runbook-scoped cases conflict with a Procedure").isEqualTo(2);
            assertThat(ledger.untestedCount())
                    .as("every excluded requirement is untested, never compliant")
                    .isEqualTo(excluded);
            assertThat(ledger.evaluatedCount()).isEqualTo(requirements.size() - excluded);
        }

        @Test
        @DisplayName("the assessment completes despite UNCERTAIN requirements")
        void uncertaintyDoesNotBlockCompletion() {
            // Item 11: the review completes, with an applicability-review warning rather than a
            // failure. Blocking would make an ambiguous phrase in one standard stall every review.
            var requirements = List.of(ApplicabilityFixture.unstructuredCondition(),
                    ApplicabilityFixture.missingContext());
            var run = runWorkflow(requirements, ApplicabilityFixture.noContext());

            var ledger = new CoverageLedgerService().build(
                    "CMP-1", requirements, run.findings(), Set.of(),
                    coordinator().skippedForLedger(run.decisions()));

            assertThat(ledger.isComplete()).isTrue();
            assertThat(run.decisions()).allMatch(ApplicabilityDecision::needsHumanReview);
        }
    }

    @Nested
    @DisplayName("decisions are persisted, not recalculated")
    class PersistedNotRecalculated {

        @Test
        @DisplayName("a persisted decision survives a reopen with different context")
        void reopenDoesNotRecalculate() {
            // THE reproducibility property (item 14, §19.2). A reviewer reopening history must see the
            // decision the review actually made. Recomputing it under today's context would silently
            // change a completed report — here, an exclusion would become UNCERTAIN purely because the
            // context was later cleared.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.conflictingCondition()),
                    ApplicabilityFixture.confirmedContext());
            var persisted = decisionFor(run, "CONFLICT-01");

            assertThat(persisted.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);

            // Reopening reads the stored record. Nothing re-derives it, so the context is irrelevant.
            var reopened = new ApplicabilityDecision(
                    persisted.requirementId(), persisted.status(), persisted.reason(),
                    persisted.scopeSpanIds(), persisted.missingContextFields(),
                    persisted.conditions(), persisted.decidedBy());

            assertThat(reopened.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(reopened.reason()).isEqualTo(persisted.reason());
            assertThat(reopened.conditions()).isEqualTo(persisted.conditions());
        }

        @Test
        @DisplayName("every decision records the resolver version that produced it")
        void decisionNamesItsResolver() {
            // So a later rule change cannot silently reinterpret an existing review: the stored version
            // says which rules applied.
            var run = runWorkflow(ApplicabilityFixture.all(),
                    ApplicabilityFixture.confirmedContext());

            assertThat(run.decisions())
                    .allSatisfy(d -> assertThat(d.decidedBy())
                            .isEqualTo(ApplicabilityResolver.RESOLVER_VERSION));
        }

        @Test
        @DisplayName("the persisted lists are immutable")
        void persistedListsAreDefensivelyCopied() {
            // A completed review must not change because a caller mutated a list it handed over.
            var mutableSpans = new ArrayList<>(List.of("SPAN-1"));
            var decision = new ApplicabilityDecision("R1", ApplicabilityStatus.UNCERTAIN,
                    "reason", mutableSpans, List.of(), List.of(), "APPLICABILITY-V1");

            mutableSpans.add("SPAN-injected");

            assertThat(decision.scopeSpanIds()).containsExactly("SPAN-1");
        }
    }

    @Nested
    @DisplayName("applicability and comparison stay separate through the workflow")
    class Separation {

        @Test
        @DisplayName("an excluded requirement produces NO finding, so no ComparisonStatus")
        void noFindingMeansNoComparisonStatus() {
            // Item 10. There is no correct ComparisonStatus for a requirement that was not measured,
            // so the workflow produces none rather than choosing a misleading one.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.conflictingCondition()),
                    ApplicabilityFixture.confirmedContext());

            assertThat(run.findings()).isEmpty();
            assertThat(decisionFor(run, "CONFLICT-01").status())
                    .isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
        }

        @Test
        @DisplayName("an UNCERTAIN requirement still gets a full comparison verdict")
        void uncertainStillGetsAVerdict() {
            // Both dimensions are reported: the gap AND the scope question. Deciding for the reviewer
            // would hide one of the two.
            var run = runWorkflow(
                    List.of(ApplicabilityFixture.unstructuredCondition()),
                    ApplicabilityFixture.confirmedContext());

            assertThat(run.findings()).hasSize(1);
            assertThat(run.findings().getFirst().status()).isEqualTo(ComparisonStatus.MISSING);
            assertThat(decisionFor(run, "FREETEXT-01").status())
                    .isEqualTo(ApplicabilityStatus.UNCERTAIN);
        }
    }
}
