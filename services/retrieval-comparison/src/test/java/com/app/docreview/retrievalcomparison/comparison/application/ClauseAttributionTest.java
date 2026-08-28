package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.comparison.ApplicabilityDecision;
import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;
import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.enums.Severity;
import com.app.docreview.contracts.retrieval.EvidenceCandidate;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import com.app.docreview.retrievalcomparison.comparison.aggregation.ClauseAggregator;
import com.app.docreview.retrievalcomparison.comparison.validation.ComparisonOutputValidator;
import com.app.docreview.retrievalcomparison.llm.ComparisonModelGateway;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Clause-level attribution under schema V1. Architecture §18.5, §16.5.
 *
 * <p><strong>The regression.</strong> Every {@link com.app.docreview.contracts.comparison.AtomicClauseResult}
 * was built with the PARENT's {@code reason} and the parent's whole span list. A three-clause finding
 * therefore showed the same multi-clause paragraph three times, and an ALIGNED clause claimed every
 * span the requirement cited — asserting an attribution the model never made.
 *
 * <p>Clause STATUS is genuinely per-clause and must keep working; only the inherited explanation and
 * evidence are removed. Those two properties are what these tests separate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClauseAttributionTest {

    @Mock private ComparisonModelGateway gateway;
    @Mock private EvidenceSpanReader spanReader;

    /** Three genuinely distinct clauses, as extraction produces them. */
    private ExtractedRequirement requirement() {
        return new ExtractedRequirement(
                "2.1-R2", "2.1", "2.1", "Implementer minimum accountability",
                List.of("SPAN-STD-1"),
                NormativeStrength.MANDATORY,
                "The implementer must perform approved steps, record execution, and stop on triggers.",
                List.of(
                        new AtomicClause("2.1-IMPL-1", "Implementer", "perform",
                                "approved steps", "", "", false),
                        new AtomicClause("2.1-IMPL-2", "Implementer", "record",
                                "actual execution", "", "", false),
                        new AtomicClause("2.1-IMPL-3", "Implementer", "stop", "",
                                "when triggers occur", "when triggers occur", false)),
                null,
                List.of(), 0.9);
    }

    private List<EvidenceCandidate> candidates() {
        return List.of(new EvidenceCandidate(
                "CHUNK-1", "PROC-V1",
                List.of("SPAN-PROC-1", "SPAN-PROC-2", "SPAN-PROC-3"),
                List.of("Implementation"), "The implementer executes and records the change.",
                1, 1, null, null));
    }

    private SearchCoverage coverage() {
        return new SearchCoverage(List.of("Implementation"), 10, 0.25, null,
                List.of(), List.of(), true, true);
    }

    /**
     * The V1 shape: two clauses satisfied, one not, with ONE reason and ONE span list for the whole
     * requirement — which is all the V1 contract can carry.
     */
    private ComparisonModelOutput output() {
        return new ComparisonModelOutput(
                "2.1-R2", ComparisonStatus.PARTIAL, GapType.WEAKER,
                List.of("2.1-IMPL-1", "2.1-IMPL-2"),
                List.of("2.1-IMPL-3"),
                List.of("SPAN-STD-1"),
                List.of("SPAN-PROC-1", "SPAN-PROC-2", "SPAN-PROC-3"),
                "2.1-IMPL-1 is satisfied because… 2.1-IMPL-2 is satisfied because… "
                        + "2.1-IMPL-3 is only partially satisfied because…",
                "Define rollback triggers for all risk levels.", 0.88, false);
    }

    /**
     * A plainly-applicable decision, as the coordinator would supply.
     *
     * <p>Applicability is not what these tests are about — clause attribution is. A real decision rather
     * than null keeps them honest about the collaborator the comparer now depends on.
     */
    private static ApplicabilityDecision applies() {
        return ApplicabilityDecision.applicable("2.1", "APPLICABILITY-RULES-V3");
    }

    private RequirementComparisonService service() {
        var service = new RequirementComparisonService(
                gateway, new ComparisonOutputValidator(), new ClauseAggregator(), spanReader);
        when(gateway.compare(any())).thenReturn(new ComparisonModelGateway.Result(
                output(), "gpt-5.4", "COMPARE-V1", "COMPARE-SCHEMA-V1",
                100, 50, 1200L, true, List.of()));
        return service;
    }

    @Test
    @DisplayName("clause statuses stay per-clause and correct")
    void clauseStatusesAreDerivedPerClause() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());

        assertThat(outcome.succeeded()).isTrue();
        var clauses = outcome.finding().clauseResults();
        assertThat(clauses).hasSize(3);
        // The satisfied/unsatisfied split IS genuine model output and must survive the fix.
        assertThat(clauses).anySatisfy(c -> {
            assertThat(c.clauseId()).isEqualTo("2.1-IMPL-1");
            assertThat(c.status()).isEqualTo(ComparisonStatus.ALIGNED);
        });
        assertThat(clauses).anySatisfy(c -> {
            assertThat(c.clauseId()).isEqualTo("2.1-IMPL-3");
            assertThat(c.status()).isEqualTo(ComparisonStatus.MISSING);
        });
    }

    @Test
    @DisplayName("the parent reason is NOT copied onto any clause")
    void parentReasonIsNotInherited() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());

        // Previously all three carried the parent's paragraph verbatim, so the UI rendered it thrice.
        assertThat(outcome.finding().clauseResults())
                .allSatisfy(c -> assertThat(c.reason()).isNull());
    }

    @Test
    @DisplayName("the parent span list is NOT copied onto any clause")
    void parentEvidenceIsNotInherited() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());

        // An ALIGNED clause previously claimed all three parent spans, asserting traceability the
        // model never established for that clause specifically (§16.5).
        assertThat(outcome.finding().clauseResults())
                .allSatisfy(c -> assertThat(c.processSpanIds()).isEmpty());
    }

    @Test
    @DisplayName("the parent finding keeps its reason and evidence")
    void parentRetainsReasonAndEvidence() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());
        var finding = outcome.finding();

        // Removing clause-level copies must not remove the requirement-level explanation, which is
        // what the UI now renders once above the clause list.
        assertThat(finding.reason()).contains("2.1-IMPL-1 is satisfied");
        assertThat(finding.processSpanIds())
                .containsExactlyInAnyOrder("SPAN-PROC-1", "SPAN-PROC-2", "SPAN-PROC-3");
        assertThat(finding.recommendedAction()).isNotBlank();
    }

    @Test
    @DisplayName("each clause carries readable text, so sibling rows are distinguishable")
    void clausesCarryDisplayText() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());
        var clauses = outcome.finding().clauseResults();

        // Without this, two clauses sharing a status render as identical rows differing only by an
        // ID suffix — which is what happened to 5.1-a "publish" and 5.1-b "observe".
        assertThat(clauses).allSatisfy(c -> assertThat(c.clauseText()).isNotBlank());
        assertThat(clauses.stream().map(c -> c.clauseText()).distinct())
                .as("three distinct clauses must render three distinct labels")
                .hasSize(3);
        assertThat(clauses).anySatisfy(c -> assertThat(c.clauseText()).contains("perform"));
        assertThat(clauses).anySatisfy(c -> assertThat(c.clauseText()).contains("record"));
        assertThat(clauses).anySatisfy(c -> assertThat(c.clauseText()).contains("stop"));
    }

    @Test
    @DisplayName("a dot-suffixed clause reference still resolves to its declared clause")
    void suffixedReferenceResolves() {
        // Validation tolerates "2.1-IMPL-1.who"; this method must resolve it the SAME way, or every
        // clause falls through to INSUFFICIENT_EVIDENCE — a silently unassessed requirement, worse
        // than the validation error it replaced.
        when(gateway.compare(any())).thenReturn(new ComparisonModelGateway.Result(
                new ComparisonModelOutput(
                        "2.1-R2", ComparisonStatus.PARTIAL, GapType.WEAKER,
                        List.of("2.1-IMPL-1.who", "2.1-IMPL-2.does"),
                        List.of("2.1-IMPL-3.when"),
                        List.of("SPAN-STD-1"),
                        List.of("SPAN-PROC-1"),
                        "reason", "action", 0.9, false),
                "gpt-5.4", "COMPARE-V1", "COMPARE-SCHEMA-V1", 100, 50, 1200L, true, List.of()));

        var service = new RequirementComparisonService(
                gateway, new ComparisonOutputValidator(), new ClauseAggregator(), spanReader);
        var outcome = service.compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());

        assertThat(outcome.succeeded()).isTrue();
        var clauses = outcome.finding().clauseResults();
        assertThat(clauses).noneSatisfy(c ->
                assertThat(c.status()).isEqualTo(ComparisonStatus.INSUFFICIENT_EVIDENCE));
        assertThat(clauses).anySatisfy(c -> {
            assertThat(c.clauseId()).isEqualTo("2.1-IMPL-1");
            assertThat(c.status()).isEqualTo(ComparisonStatus.ALIGNED);
        });
        assertThat(clauses).anySatisfy(c -> {
            assertThat(c.clauseId()).isEqualTo("2.1-IMPL-3");
            assertThat(c.status()).isEqualTo(ComparisonStatus.MISSING);
        });
    }

    @Test
    @DisplayName("gapType is still recorded on a missing clause")
    void missingClauseKeepsGapType() {
        var outcome = service().compare("CMP-1", "PROC-V1", "STD-V1", "REQSET-V1",
                requirement(), Severity.MEDIUM, candidates(), coverage(), applies());

        // gapType IS clause-specific enough to keep: it is derived from this clause's own status.
        assertThat(outcome.finding().clauseResults())
                .filteredOn(c -> c.status() == ComparisonStatus.MISSING)
                .allSatisfy(c -> assertThat(c.gapType()).isNotNull());
    }
}
