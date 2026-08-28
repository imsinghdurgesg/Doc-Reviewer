package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver;
import com.app.docreview.retrievalcomparison.comparison.application.ApplicabilityResolver.ConfirmedContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic applicability resolver. Architecture §12.5, §17.6.
 *
 * <p>Four properties carry the weight, each guarding a distinct way of being wrong:
 *
 * <ul>
 *   <li><strong>Default APPLICABLE.</strong> Most requirements apply unconditionally. Defaulting to
 *       anything weaker marks the ordinary case as suspect, burying the genuine exceptions and letting
 *       a classification failure quietly shrink the review.</li>
 *   <li><strong>Only a CLEAR conflict with CONFIRMED context excludes.</strong> Inferred metadata is a
 *       guess; excluding on one would let a mid-confidence label remove a real obligation.</li>
 *   <li><strong>Doubt does not exclude.</strong> Unresolvable conditions yield UNCERTAIN, still
 *       compared.</li>
 *   <li><strong>No model call.</strong> Free text is flagged with evidence attached, never interpreted
 *       (the Phase 2 bound).</li>
 * </ul>
 */
class ApplicabilityResolverTest {

    private final ApplicabilityResolver resolver = new ApplicabilityResolver();

    private static ConfirmedContext confirmed(String documentType) {
        return new ConfirmedContext(documentType);
    }

    private static Applicability scopedTo(String... documentTypes) {
        return new Applicability(List.of(documentTypes), List.of());
    }

    private static Applicability withConditions(String... conditions) {
        return new Applicability(List.of(), List.of(conditions));
    }

    @Nested
    @DisplayName("item 5: no explicit condition means APPLICABLE")
    class Unconditional {

        @Test
        @DisplayName("a requirement stating no condition applies, with no reason recorded")
        void unconditionalApplies() {
            var decision = resolver.resolve(null, confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(decision.reason())
                    .as("a reason on the ordinary case would be noise on every requirement")
                    .isNull();
            assertThat(decision.needsScopeContext()).isFalse();
            assertThat(decision.missingContextFields()).isEmpty();
        }

        @Test
        @DisplayName("empty and blank condition lists state nothing")
        void emptyListsAreUnconditional() {
            // An extraction that produced `conditions: [""]` has stated nothing. Reading that as a
            // condition would push an ordinary requirement into UNCERTAIN and flag it for review.
            assertThat(resolver.resolve(new Applicability(List.of(), List.of()), confirmed("Procedure"))
                    .status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(resolver.resolve(new Applicability(List.of(""), List.of("  ")),
                    confirmed("Procedure")).status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(resolver.resolve(new Applicability(null, null), confirmed("Procedure"))
                    .status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("an unconditional requirement applies even with NO confirmed context")
        void unconditionalIgnoresMissingContext() {
            // The fast path must not depend on metadata. A document with nothing confirmed still gets
            // every unconditional requirement evaluated — which is the whole corpus today.
            assertThat(resolver.resolve(null, ConfirmedContext.empty()).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
            assertThat(resolver.resolve(null, null).status())
                    .as("a null context must not throw")
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("a universally-scoped requirement applies")
        void universalScopeApplies() {
            // "all documents" states a condition syntactically while imposing none. Matching it
            // literally against a document type would exclude everything.
            for (String universal : new String[]{"all", "any", "All Documents", "*"}) {
                assertThat(resolver.resolve(scopedTo(universal), confirmed("Runbook")).status())
                        .as("scope %s must not exclude", universal)
                        .isEqualTo(ApplicabilityStatus.APPLICABLE);
            }
        }
    }

    @Nested
    @DisplayName("item 6: a condition matching confirmed context is APPLICABLE")
    class ClearMatch {

        @Test
        @DisplayName("a matching document type applies")
        void matchingTypeApplies() {
            assertThat(resolver.resolve(scopedTo("Procedure"), confirmed("Procedure")).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("matching ignores case and whitespace")
        void matchingIsLenient() {
            // Values arrive from a form, a model, and a standard's prose. Requiring exact bytes would
            // exclude a requirement for a cosmetic difference.
            assertThat(resolver.resolve(scopedTo("procedure"), confirmed("  Procedure ")).status())
                    .isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("any one matching type in a list is enough")
        void anyMatchSuffices() {
            assertThat(resolver.resolve(scopedTo("Runbook", "Procedure"), confirmed("Procedure"))
                    .status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }

        @Test
        @DisplayName("a matched document type does NOT resolve a separate free-text condition")
        void matchDoesNotSwallowOtherConditions() {
            // Matching the type answers one question. A separate "only where personal data is
            // processed" clause is still unresolved, and reporting APPLICABLE would silently assert
            // the platform had judged it.
            var decision = resolver.resolve(
                    new Applicability(List.of("Procedure"), List.of("only where personal data")),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
        }
    }

    @Nested
    @DisplayName("item 7: a clear conflict with confirmed context is NOT_APPLICABLE")
    class ClearConflict {

        @Test
        @DisplayName("a non-matching confirmed type excludes, with a reason")
        void conflictExcludes() {
            // The state that stops an inapplicable control being reported as a gap. The reason is
            // mandatory: an exclusion nobody can inspect is indistinguishable from a lost requirement.
            var decision = resolver.resolve(scopedTo("Runbook"), confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
            assertThat(decision.reason()).contains("Runbook", "Procedure");
            assertThat(decision.shouldCompare()).isFalse();
        }

        @Test
        @DisplayName("an exclusion still attaches scope evidence")
        void exclusionCarriesEvidence() {
            // A reviewer overruling the exclusion needs the standard's own scope wording, not just the
            // platform's verdict.
            assertThat(resolver.resolve(scopedTo("Runbook"), confirmed("Procedure"))
                    .needsScopeContext()).isTrue();
        }

        @Test
        @DisplayName("the stated conditions are carried verbatim")
        void conditionsCarriedVerbatim() {
            // So the reviewer reads what the standard said, not a paraphrase of it.
            var decision = resolver.resolve(
                    new Applicability(List.of("Runbook"), List.of("where cloud-hosted")),
                    confirmed("Procedure"));

            assertThat(decision.conditions()).containsExactly("where cloud-hosted");
        }
    }

    @Nested
    @DisplayName("items 8 and 9: missing or unconfirmed context is UNCERTAIN, never excluded")
    class UnknownContext {

        @Test
        @DisplayName("an UNCONFIRMED document type is UNCERTAIN, never NOT_APPLICABLE")
        void unconfirmedIsNeverExcluded() {
            // THE property that protects the review (item 9). Absence of a confirmed label is absence
            // of information — excluding on it would drop requirements because METADATA is missing
            // rather than because the standard says so.
            var decision = resolver.resolve(scopedTo("Runbook"), ConfirmedContext.empty());

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.shouldCompare())
                    .as("unknown scope must still be compared")
                    .isTrue();
        }

        @Test
        @DisplayName("UNCERTAIN names the missing context field")
        void namesTheMissingField() {
            // So the reviewer is told what to supply rather than left to infer it from prose.
            var decision = resolver.resolve(scopedTo("Runbook"), ConfirmedContext.empty());

            assertThat(decision.missingContextFields()).containsExactly("documentType");
        }

        @Test
        @DisplayName("a blank confirmed value counts as unconfirmed")
        void blankIsUnconfirmed() {
            // An empty form field is not a confirmation. Treating "" as a confirmed type would compare
            // it against the requirement's scope and produce a spurious exclusion.
            for (String blank : new String[]{"", "   "}) {
                assertThat(resolver.resolve(scopedTo("Runbook"), confirmed(blank)).status())
                        .as("a blank value must not be treated as confirmed")
                        .isEqualTo(ApplicabilityStatus.UNCERTAIN);
            }
        }
    }

    @Nested
    @DisplayName("item 12: free text is flagged with evidence, never interpreted")
    class FreeText {

        @Test
        @DisplayName("an unstructured condition yields UNCERTAIN and requests scope evidence")
        void freeTextRequestsEvidence() {
            // "applies only where personal data is processed" cannot be decided from the phrase alone,
            // and Phase 2 explicitly does not send it to a model. UNCERTAIN with the scope chunks
            // attached is the honest outcome.
            var decision = resolver.resolve(
                    withConditions("only where personal data is processed"), confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.UNCERTAIN);
            assertThat(decision.needsScopeContext()).isTrue();
            assertThat(decision.shouldCompare()).isTrue();
        }

        @Test
        @DisplayName("free text lists NO missing context field")
        void freeTextNamesNoField() {
            // No confirmed value would resolve free text, so sending the reviewer to fill in a form
            // would waste their time and imply the platform would then decide.
            var decision = resolver.resolve(
                    withConditions("only where personal data is processed"), confirmed("Procedure"));

            assertThat(decision.missingContextFields()).isEmpty();
        }

        @Test
        @DisplayName("scope evidence is NOT requested for an unconditional requirement")
        void noEvidenceWithoutCondition() {
            // The bound that keeps the cost proportional to conditional requirements rather than all
            // of them.
            assertThat(resolver.resolve(null, confirmed("Procedure")).needsScopeContext()).isFalse();
            assertThat(resolver.resolve(scopedTo("Procedure"), confirmed("Procedure"))
                    .needsScopeContext())
                    .as("a satisfied document-type condition needs no scope text")
                    .isFalse();
        }

        @Test
        @DisplayName("the reason summarises rather than dumping every condition")
        void reasonIsSummarised() {
            var decision = resolver.resolve(
                    withConditions("first condition", "second", "third"), confirmed("Procedure"));

            assertThat(decision.reason()).contains("first condition").contains("2 more");
        }

        @Test
        @DisplayName("a type conflict decides before free text is considered")
        void conflictWinsOverFreeText() {
            // Structural exclusion is decidable; the free-text condition is not. Reporting UNCERTAIN
            // here would flag a requirement for review when it demonstrably does not apply.
            var decision = resolver.resolve(
                    new Applicability(List.of("Runbook"), List.of("where cloud-hosted")),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.NOT_APPLICABLE);
        }

        @Test
        @DisplayName("null elements inside a condition list do not crash")
        void nullElementsTolerated() {
            var decision = resolver.resolve(
                    new Applicability(Arrays.asList(null, "Procedure"), Arrays.asList((String) null)),
                    confirmed("Procedure"));

            assertThat(decision.status()).isEqualTo(ApplicabilityStatus.APPLICABLE);
        }
    }

    @Nested
    @DisplayName("the status enum's own contract")
    class StatusContract {

        @Test
        @DisplayName("only NOT_APPLICABLE withholds a requirement from comparison")
        void onlyNotApplicableExcludes() {
            assertThat(ApplicabilityStatus.APPLICABLE.shouldCompare()).isTrue();
            assertThat(ApplicabilityStatus.UNCERTAIN.shouldCompare())
                    .as("doubt does not exclude")
                    .isTrue();
            assertThat(ApplicabilityStatus.NOT_APPLICABLE.shouldCompare()).isFalse();
        }

        @Test
        @DisplayName("both non-default states ask for a human to check the scope")
        void nonDefaultsNeedScopeReview() {
            // Opposite reasons, same need: an exclusion removed a requirement from the review, and
            // UNCERTAIN means the platform could not tell whether it should have.
            assertThat(ApplicabilityStatus.APPLICABLE.needsScopeReview()).isFalse();
            assertThat(ApplicabilityStatus.NOT_APPLICABLE.needsScopeReview()).isTrue();
            assertThat(ApplicabilityStatus.UNCERTAIN.needsScopeReview()).isTrue();
        }

        @Test
        @DisplayName("item 4: exactly the three specified values, APPLICABLE first")
        void exactlyThreeValues() {
            assertThat(ApplicabilityStatus.values()).containsExactly(
                    ApplicabilityStatus.APPLICABLE,
                    ApplicabilityStatus.NOT_APPLICABLE,
                    ApplicabilityStatus.UNCERTAIN);
        }
    }
}
