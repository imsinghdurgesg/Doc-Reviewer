package com.app.docreview.systemtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluation arithmetic. Architecture §15.1.
 *
 * <p>These run without a deployment, unlike the evaluation itself. They matter because the metrics are
 * about to inform a decision on whether to rebuild retrieval — and a metric that flatters the system
 * is worse than no metric, since it produces confident wrong conclusions.
 *
 * <p>Each case pins a definition that could plausibly have been written the other way.
 */
class RetrievalMetricsTest {

    private static RetrievalMetrics.CaseResult result(
            List<String> expected, List<String> ranked, List<String> selected) {
        return new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", expected, "IT", List.of()),
                ranked, selected, List.of(), List.of(), List.of(), 10L);
    }

    @Test
    @DisplayName("Recall@k counts a hit when ANY expected standard is in the top k")
    void recallIsAnyNotAll() {
        // A document governed by four standards cannot meaningfully be asked whether all four are in
        // the top 1. What matters is whether the reviewer sees something correct early.
        var hit = result(List.of("a", "b"), List.of("z", "a", "y"), List.of("z"));

        assertThat(RetrievalMetrics.recallAt(List.of(hit), 1)).isEqualTo(0.0);
        assertThat(RetrievalMetrics.recallAt(List.of(hit), 5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a case expecting nothing is excluded from recall, not counted as a hit")
    void negativesExcludedFromRecall() {
        // Counting them as hits would let a system that retrieves nothing for everything score 100%.
        var negative = result(List.of(), List.of(), List.of());

        assertThat(RetrievalMetrics.recallAt(List.of(negative), 5)).isNaN();
    }

    @Test
    @DisplayName("multi-standard recall reports the FRACTION found, not whether any was")
    void multiStandardIsFractional() {
        // The metric that separates "looks complete" from "is complete": Recall@5 can be 100% while
        // only one of four applicable standards was surfaced.
        var partial = result(List.of("a", "b", "c", "d"), List.of("a", "z", "y"), List.of("a"));

        assertThat(RetrievalMetrics.recallAt(List.of(partial), 5)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.multiStandardRecall(List.of(partial), 5)).isEqualTo(0.25);
    }

    @Test
    @DisplayName("single-standard cases are excluded from multi-standard recall")
    void multiStandardIgnoresSingles() {
        var single = result(List.of("a"), List.of("a"), List.of("a"));

        assertThat(RetrievalMetrics.multiStandardRecall(List.of(single), 5)).isNaN();
    }

    @Test
    @DisplayName("precision falls as irrelevant candidates are added")
    void precisionPenalisesNoise() {
        // Without this, retrieving twenty candidates would score perfectly on recall and make the
        // selection page useless.
        var tight = result(List.of("a"), List.of("a"), List.of("a"));
        var noisy = result(List.of("a"), List.of("a", "w", "x", "y", "z"), List.of("a"));

        assertThat(RetrievalMetrics.precisionAt(List.of(tight), 5)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.precisionAt(List.of(noisy), 5)).isEqualTo(0.2);
    }

    @Test
    @DisplayName("no-relevant accuracy rewards selecting nothing, not ranking nothing")
    void honestyMetric() {
        // Retrieval may legitimately surface candidates; what must not happen is COMPARING against one
        // when the corpus covers nothing. So it is measured on selection, not on the ranking.
        var refused = result(List.of(), List.of("a", "b"), List.of());
        var guessed = result(List.of(), List.of("a", "b"), List.of("a"));

        assertThat(RetrievalMetrics.noRelevantStandardAccuracy(List.of(refused))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.noRelevantStandardAccuracy(List.of(guessed))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("wrong-department rate is n/a when candidates carry no department")
    void wrongDepartmentNeedsTheField() {
        // True today: the candidate payload has no department until Phase 1. Reporting n/a is honest;
        // reporting 0% would read as "no wrong-department selections", which is not what was measured.
        var noDepartment = result(List.of("a"), List.of("a"), List.of("a"));

        assertThat(RetrievalMetrics.wrongDepartmentRate(List.of(noDepartment))).isNaN();
    }

    @Test
    @DisplayName("wrong-department rate counts a selection from another department")
    void wrongDepartmentCounted() {
        var mismatch = new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", List.of("a"), "IT", List.of()),
                List.of("a"), List.of("a"), List.of("PROCUREMENT"), List.of(), List.of(), 10L);
        var match = new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-2", "case", List.of("a"), "IT", List.of()),
                List.of("a"), List.of("a"), List.of("IT"), List.of(), List.of(), 10L);

        assertThat(RetrievalMetrics.wrongDepartmentRate(List.of(mismatch))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.wrongDepartmentRate(List.of(match))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("the report renders with no cases rather than dividing by zero")
    void reportSurvivesEmptyInput() {
        assertThat(RetrievalMetrics.report(List.of())).contains("cases", "0");
    }

    // ---------------------------------------------------------------------------------------------
    // wrongVersionRate — the right standard at the wrong version.
    //
    // The more dangerous of the two selection failures: subject matter matches, findings read
    // plausibly, and the document was measured against rules not in force. Wrong-standard produces
    // obviously irrelevant findings a reviewer questions; wrong-version produces credible ones.
    // ---------------------------------------------------------------------------------------------

    /** A case that commits to a specific expected version. */
    private static RetrievalMetrics.CaseResult versioned(
            List<String> expectedVersions, List<String> selectedVersions) {
        return new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", List.of("a"), null,
                        expectedVersions),
                List.of("a"), List.of("a"), List.of(), selectedVersions, List.of("ACTIVE"), 10L);
    }

    @Test
    @DisplayName("selecting a different version of the right standard is a wrong-version failure")
    void wrongVersionCounted() {
        // The corpus case: one standard with versions v2, v3, vv1. Any lexical ordering picks a wrong
        // one while the ranking looks entirely healthy.
        assertThat(RetrievalMetrics.wrongVersionRate(List.of(versioned(List.of("v-3"), List.of("v-2")))))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("selecting the expected version is not a failure")
    void rightVersionPasses() {
        assertThat(RetrievalMetrics.wrongVersionRate(List.of(versioned(List.of("v-3"), List.of("v-3")))))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("any overlap with the expected versions counts as correct")
    void overlapCountsAsCorrect() {
        // Selecting the right version alongside another is a PRECISION question, measured by
        // precisionAt. Counting it here would charge one selection under two metrics.
        assertThat(RetrievalMetrics.wrongVersionRate(
                List.of(versioned(List.of("v-3"), List.of("v-2", "v-3")))))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("a case that names no expected version is SKIPPED, not scored")
    void unlabelledVersionsAreSkipped() {
        // Labelling only the standard is a weaker but honest claim. Scoring it would either invent a
        // stricter expectation or dilute the rate with cases that cannot fail.
        var unlabelled = new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", List.of("a"), null, List.of()),
                List.of("a"), List.of("a"), List.of(), List.of("v-2"), List.of("ACTIVE"), 10L);

        assertThat(RetrievalMetrics.wrongVersionRate(List.of(unlabelled)))
                .as("n/a, not 0% and not 100%")
                .isNaN();
    }

    @Test
    @DisplayName("a recall miss is NOT charged as a wrong-version failure")
    void recallMissIsNotAVersionFailure() {
        // The standard was never selected at all, so this is a retrieval failure that recallAt already
        // measures. Charging it here too would double-count one error and make both metrics look worse
        // than they are.
        var missed = new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", List.of("a"), null, List.of("v-3")),
                List.of("b"), List.of("b"), List.of(), List.of("v-9"), List.of("ACTIVE"), 10L);

        assertThat(RetrievalMetrics.wrongVersionRate(List.of(missed))).isNaN();
    }

    @Test
    @DisplayName("version comparison ignores case")
    void versionComparisonIsLenient() {
        assertThat(RetrievalMetrics.wrongVersionRate(
                List.of(versioned(List.of("V-3"), List.of("v-3")))))
                .isEqualTo(0.0);
    }

    // ---------------------------------------------------------------------------------------------
    // supersededCandidateRate — was something stale selected?
    //
    // Needs only the lifecycle already on the candidate, so unlike wrongVersionRate it works before a
    // labelled set exists.
    // ---------------------------------------------------------------------------------------------

    private static RetrievalMetrics.CaseResult withLifecycles(List<String> lifecycles) {
        return new RetrievalMetrics.CaseResult(
                new RetrievalEvaluationSet.Case("PROC-1", "case", List.of("a"), null, List.of()),
                List.of("a"), List.of("a"), List.of(), List.of("v-1"), lifecycles, 10L);
    }

    @Test
    @DisplayName("DEPRECATED and ARCHIVED selections are superseded")
    void supersededCounted() {
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("DEPRECATED"))))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("ARCHIVED"))))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an ACTIVE selection is not superseded")
    void activeIsNotSuperseded() {
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("ACTIVE"))))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("DRAFT is NOT counted as superseded")
    void draftIsNotSuperseded() {
        // A draft was never in force. Offering one is a different-in-kind error — measuring against
        // rules not yet adopted, rather than against outdated ones — and conflating the two hides
        // which is happening.
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("DRAFT"))))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("one superseded selection among several taints the case")
    void anySupersededTaintsTheCase() {
        // The document WAS measured against a stale version, whatever else was also selected.
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("ACTIVE", "ARCHIVED"))))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("cases with no lifecycle data report n/a rather than 0%")
    void missingLifecycleIsNotAPass() {
        // Before the payload carries lifecycle, "0% superseded" would be a false clean bill of health.
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of())))).isNaN();
    }

    @Test
    @DisplayName("lifecycle matching ignores case and whitespace")
    void lifecycleMatchingIsLenient() {
        assertThat(RetrievalMetrics.supersededCandidateRate(
                List.of(withLifecycles(List.of("  deprecated "))))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("both new metrics appear in the report")
    void newMetricsAreReported() {
        // A metric computed and not printed is a metric nobody reads.
        var report = RetrievalMetrics.report(List.of(versioned(List.of("v-3"), List.of("v-2"))));

        assertThat(report).contains("Wrong-version rate", "Superseded-candidate rate");
    }

    @Test
    @DisplayName("the superseded definition matches the service's")
    void supersededMatchesServiceDefinition() {
        // RetrievalMetrics duplicates StandardVersionPreference.isSuperseded because §6 forbids the
        // system-tests module depending on a service jar. This pins the two to agree: if the service
        // ever adds a superseded lifecycle, this fails and names the drift.
        for (String superseded : new String[]{"DEPRECATED", "ARCHIVED"}) {
            assertThat(RetrievalMetrics.supersededCandidateRate(
                    List.of(withLifecycles(List.of(superseded)))))
                    .as("%s must count as superseded in both definitions", superseded)
                    .isEqualTo(1.0);
        }
        for (String live : new String[]{"ACTIVE", "DRAFT"}) {
            assertThat(RetrievalMetrics.supersededCandidateRate(
                    List.of(withLifecycles(List.of(live)))))
                    .as("%s must NOT count as superseded in either definition", live)
                    .isEqualTo(0.0);
        }
    }
}
