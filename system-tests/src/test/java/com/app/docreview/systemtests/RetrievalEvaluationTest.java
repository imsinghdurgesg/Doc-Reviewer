package com.app.docreview.systemtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures how well standard selection performs at real corpus size. Architecture §15.1.
 *
 * <p><strong>Why this exists before any retrieval change.</strong> The deployment has three standards,
 * against which selection cannot fail. The decision to rebuild retrieval for a thousand
 * department-specific standards should rest on a measurement at three hundred, not on anticipation —
 * and "re-run the evaluation after the change" needs something to re-run.
 *
 * <p><strong>Not a pass/fail test.</strong> It reports numbers. There is no threshold to assert,
 * because nobody knows yet what Recall@5 this corpus supports — inventing one would either pass
 * trivially or fail without telling anyone anything. Once a baseline exists, a regression guard can be
 * added against THAT figure.
 *
 * <p>Skips when the labelled set is absent, and says so. A missing file means "not labelled yet", and
 * must not be mistaken for a pass.
 *
 * <pre>
 *   1. Load a corpus slice through the usual GCS sync
 *   2. Upload the process documents to be evaluated
 *   3. Write system-tests/retrieval-eval.json with their ids and expected standards
 *   4. ./mvnw -pl system-tests test -Dtest=RetrievalEvaluationTest
 * </pre>
 */
class RetrievalEvaluationTest {

    private static final Path SET_PATH = Path.of("retrieval-eval.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("reports retrieval quality against the labelled set")
    void measure() throws Exception {
        var set = RetrievalEvaluationSet.load(SET_PATH);
        Assumptions.assumeFalse(set.isEmpty(),
                "No labelled set at " + SET_PATH.toAbsolutePath()
                        + " — see this class's javadoc. Skipped, NOT passed.");

        var client = SystemTestEnvironment.retrievalComparison();
        var results = new ArrayList<RetrievalMetrics.CaseResult>();

        for (var testCase : set.cases()) {
            long started = System.nanoTime();
            JsonNode body = client.get()
                    .uri("/internal/v1/standard-candidates/{id}?limit=20",
                            testCase.documentVersionId())
                    .retrieve()
                    .body(JsonNode.class);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            var ranked = new ArrayList<String>();
            var selected = new ArrayList<String>();
            var selectedDepartments = new ArrayList<String>();
            // Version identity and lifecycle, for wrongVersionRate and supersededCandidateRate.
            // Collected in the same order as `selected` so the three lists stay aligned.
            var selectedVersionIds = new ArrayList<String>();
            var selectedLifecycles = new ArrayList<String>();

            var candidates = body == null ? null : body.get("candidates");
            if (candidates != null) {
                for (JsonNode candidate : candidates) {
                    String key = text(candidate, "standardKey");
                    if (key == null) {
                        continue;
                    }
                    // Ranked includes rejected candidates: the API returns them in score order, and a
                    // standard retrieved-then-excluded is a tuning failure rather than a retrieval one.
                    ranked.add(key);
                    if (candidate.path("selected").asBoolean(false)) {
                        selected.add(key);
                        // Phase 1 adds department to the candidate payload. Absent today, so this is
                        // read defensively — the wrong-department metric reports n/a until it exists,
                        // which is honest rather than silently scoring zero.
                        String department = text(candidate, "department");
                        if (department != null) {
                            selectedDepartments.add(department);
                        }
                        // Added unconditionally, including when absent, so index i of these lists
                        // still refers to selected.get(i). Filtering nulls here would silently
                        // misalign lifecycle with the candidate it belongs to.
                        selectedVersionIds.add(text(candidate, "standardVersionId"));
                        selectedLifecycles.add(text(candidate, "lifecycle"));
                    }
                }
            }

            results.add(new RetrievalMetrics.CaseResult(
                    testCase, ranked, selected, selectedDepartments,
                    selectedVersionIds, selectedLifecycles, latencyMs));
        }

        // Printed rather than asserted. The output is the deliverable — a baseline to compare the next
        // run against, and the input to deciding whether retrieval needs rebuilding at all.
        System.out.println(RetrievalMetrics.report(results));

        // Written alongside the set so two runs can be diffed directly.
        java.nio.file.Files.writeString(
                Path.of("target/retrieval-eval-report.txt"), RetrievalMetrics.report(results));
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
