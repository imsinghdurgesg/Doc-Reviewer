package com.app.docreview.systemtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped evaluation-set template actually loads. Architecture §19.2.
 *
 * <p><strong>Why this needs a test.</strong> The template is the documented format, and it carries a
 * {@code _comment} array explaining every field. A plain {@code ObjectMapper} rejects unknown
 * properties, so the file we tell people to copy would have failed to parse — and the failure surfaces
 * only when someone tries to run a real evaluation, which is exactly the moment they cannot afford to
 * debug the harness.
 *
 * <p>The loader also has to tolerate an ABSENT file, because the harness exists before the labelled set
 * does. An empty set must be reported as skipped rather than passed.
 */
class RetrievalEvaluationSetTest {

    private static final Path TEMPLATE = Path.of("retrieval-eval.example.json");

    @Test
    @DisplayName("the shipped template parses, comments and all")
    void templateLoads() throws Exception {
        assertThat(TEMPLATE).as("test must run from the module root").exists();

        var set = RetrievalEvaluationSet.load(TEMPLATE);

        assertThat(set.isEmpty()).isFalse();
        assertThat(set.cases()).isNotEmpty();
    }

    @Test
    @DisplayName("the template demonstrates the optional version field")
    void templateShowsVersionField() throws Exception {
        // The field is optional, so a template that omitted it entirely would leave wrongVersionRate
        // undiscoverable — and a metric nobody knows how to enable is a metric that reports n/a for
        // ever.
        var set = RetrievalEvaluationSet.load(TEMPLATE);

        assertThat(set.cases())
                .allSatisfy(c -> assertThat(c.expectedStandardVersionIds()).isNotNull());
    }

    @Test
    @DisplayName("the template includes at least one negative case")
    void templateIncludesNegativeCase() throws Exception {
        // A set with no negatives cannot detect the system's inability to say "nothing applies", which
        // is the failure noRelevantStandardAccuracy exists for. The template models one so whoever
        // copies it starts with the honest shape rather than only positive cases.
        var set = RetrievalEvaluationSet.load(TEMPLATE);

        assertThat(set.cases().stream().anyMatch(RetrievalEvaluationSet.Case::expectsNothing))
                .as("the template must model a not-covered document")
                .isTrue();
    }

    @Test
    @DisplayName("an absent file loads as empty rather than throwing")
    void absentFileIsEmpty() throws Exception {
        // The harness has to compile and run before the corpus is labelled. Empty means "not labelled
        // yet", and the evaluation reports it as skipped — so it cannot be mistaken for a pass.
        var set = RetrievalEvaluationSet.load(Path.of("does-not-exist-" + hashCode() + ".json"));

        assertThat(set.isEmpty()).isTrue();
    }
}
