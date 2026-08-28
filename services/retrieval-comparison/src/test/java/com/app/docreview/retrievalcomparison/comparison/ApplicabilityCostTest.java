package com.app.docreview.retrievalcomparison.comparison;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applicability resolution costs nothing per requirement. Architecture §10.5, §12.5.
 *
 * <p><strong>Why a source assertion.</strong> This is a COST property, not a behavioural one. A resolver
 * that quietly gained a model call would still return correct answers — every behavioural test would
 * pass — while doubling the model spend and latency of every review. A 200-requirement comparison would
 * go from 200 calls to 400, and the only visible symptom would be the bill and the wait.
 *
 * <p>Injecting a gateway and asserting zero interactions would be the stronger test, but the resolver
 * takes no collaborators at all — which is precisely the property worth protecting. Asserting on the
 * source is how "takes no collaborators" is pinned.
 */
class ApplicabilityCostTest {

    private static final Path RESOLVER = Path.of(
            "src/main/java/com/app/docreview/retrievalcomparison/comparison/application/"
                    + "ApplicabilityResolver.java");

    private String source() throws IOException {
        assertThat(RESOLVER).as("test must run from the module root").exists();
        return Files.readString(RESOLVER, StandardCharsets.UTF_8);
    }

    /** Source with comment lines stripped, so prose about gateways cannot satisfy an assertion. */
    private String code() throws IOException {
        return source().lines()
                .filter(line -> {
                    String t = line.strip();
                    return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("the resolver holds no model gateway and no vector store")
    void noModelOrRetrievalDependency() throws IOException {
        var code = code();

        // Any of these would mean a per-requirement call or search. Checked against comment-stripped
        // source because this file's javadoc discusses model calls at length — an earlier test in this
        // project passed by matching a word inside the comment explaining the hazard.
        assertThat(code).doesNotContain("ModelGateway");
        assertThat(code).doesNotContain("VectorStorePort");
        assertThat(code).doesNotContain("EmbeddingGateway");
        assertThat(code).doesNotContain("ChatClient");
        assertThat(code).doesNotContain("RestClient");
    }

    @Test
    @DisplayName("the resolver has no injected collaborators at all")
    void noInjectedFields() throws IOException {
        // A stateless service with no fields cannot make a call. If a future change needs one, this
        // fails and forces the cost question to be asked explicitly rather than absorbed.
        assertThat(code())
                .as("no `private final` dependency may be added without revisiting the cost bound")
                .doesNotContain("private final");
    }

    @Test
    @DisplayName("the comparer does not resolve applicability itself — it is given the decision")
    void comparerDoesNotResolveIndependently() throws IOException {
        // THE BUG THIS PINS. The comparer used to hold its own ApplicabilityResolver and call the
        // extraction-only resolve() while building the finding. So one requirement got TWO independent
        // decisions: the coordinator's governed one drove the skip gate and the coverage ledger, and a
        // second, ungoverned one drove what the reviewer actually read.
        //
        // They could disagree, and the human-reviewed answer was the one being thrown away — invisibly,
        // because both paths produce a plausible status. A live comparison confirmed it: 26 findings came
        // back UNCERTAIN from the path that had never consulted a single reviewed override.
        var comparer = Files.readString(Path.of(
                        "src/main/java/com/app/docreview/retrievalcomparison/comparison/application/"
                                + "RequirementComparisonService.java"), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> {
                    String t = line.strip();
                    return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
                })
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(comparer)
                .as("the comparer must receive the decision, not make a second one")
                .doesNotContain("ApplicabilityResolver");
    }

    @Test
    @DisplayName("the unconditional case returns before any condition analysis")
    void unconditionalReturnsEarly() {
        // The fast path is what makes the common case free: most requirements state no condition, so
        // resolving them must do no work.
        //
        // Asserted BEHAVIOURALLY. An earlier version matched the literal source of the guard clause and
        // broke the moment that clause was refactored — a proxy assertion failing on a correct change,
        // which is the second time this file has had that problem. What matters is the observable
        // outcome: APPLICABLE, no reason, no evidence gathered, no context requested.
        var resolver = new com.app.docreview.retrievalcomparison.comparison.application
                .ApplicabilityResolver();
        var noContext = com.app.docreview.retrievalcomparison.comparison.application
                .ApplicabilityResolver.ConfirmedContext.empty();

        for (var applicability : java.util.Arrays.asList(
                null,
                new com.app.docreview.contracts.requirement.Applicability(
                        java.util.List.of(), java.util.List.of()),
                // Blanks state nothing, so they must take the same free path.
                new com.app.docreview.contracts.requirement.Applicability(
                        java.util.List.of(""), java.util.List.of("  ")))) {

            var decision = resolver.resolve(applicability, noContext);

            assertThat(decision.status())
                    .isEqualTo(com.app.docreview.contracts.enums.ApplicabilityStatus.APPLICABLE);
            assertThat(decision.needsScopeContext())
                    .as("the fast path must not trigger a chunk read")
                    .isFalse();
            assertThat(decision.conditionSourceSpanIds()).isEmpty();
            assertThat(decision.reason())
                    .as("a reason on the ordinary case would be noise on every requirement")
                    .isNull();
        }
    }

    @Test
    @DisplayName("scope context is requested only AFTER a condition has been established")
    void scopeContextIsConditional() throws IOException {
        // Item 12's bound: SCOPE and APPLICABILITY chunks exist to resolve or evidence a real
        // condition, never to re-litigate applicability for every requirement.
        //
        // Asserted behaviourally rather than by counting `true` literals. An earlier version of this
        // test counted them and broke the moment exclusions also began attaching evidence — the count
        // was a proxy for the property, and a proxy that fails on a correct change is worse than no
        // test. What actually matters is that the two no-condition exits do NOT request context.
        var resolver = new com.app.docreview.retrievalcomparison.comparison.application
                .ApplicabilityResolver();
        var noContext = com.app.docreview.retrievalcomparison.comparison.application
                .ApplicabilityResolver.ConfirmedContext.empty();

        assertThat(resolver.resolve(null, noContext).needsScopeContext())
                .as("an unconditional requirement must not trigger a chunk read")
                .isFalse();
        assertThat(resolver.resolve(
                new com.app.docreview.contracts.requirement.Applicability(
                        java.util.List.of(), java.util.List.of()), noContext).needsScopeContext())
                .as("empty condition lists must not trigger a chunk read")
                .isFalse();
        assertThat(resolver.resolve(
                new com.app.docreview.contracts.requirement.Applicability(
                        java.util.List.of("Procedure"), java.util.List.of()),
                new com.app.docreview.retrievalcomparison.comparison.application
                        .ApplicabilityResolver.ConfirmedContext("Procedure")).needsScopeContext())
                .as("a satisfied structured condition needs no scope text")
                .isFalse();
    }
}
