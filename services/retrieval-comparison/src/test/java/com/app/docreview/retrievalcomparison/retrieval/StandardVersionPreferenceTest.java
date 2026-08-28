package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.retrievalcomparison.retrieval.document.StandardVersionPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default version of a standard is chosen by LIFECYCLE, never by version label. §8.1, §15.1.
 *
 * <p><strong>Why lexical sorting is not an option.</strong> Version labels are administrator-entered
 * free text. Sorting them produces confidently wrong answers that are easy to miss in review:
 * {@code "v10"} sorts before {@code "v9"}; {@code "2.10"} before {@code "2.9"}; and the live corpus
 * contains a standard whose versions are labelled {@code "v2"}, {@code "v3"}, and {@code "vv1"} — where
 * {@code "vv1"} sorts LAST alphabetically but is not the newest by any reading.
 *
 * <p>Picking the wrong version of the right standard is a distinct and worse failure than picking the
 * wrong standard: the findings look entirely plausible because the subject matter matches, while the
 * document is measured against rules that are not in force.
 */
class StandardVersionPreferenceTest {

    /** A stand-in for a candidate, so the comparator can be exercised without a vector store. */
    private record Candidate(String label, String lifecycle, long indexedAt) {
    }

    private static List<Candidate> sorted(List<Candidate> candidates) {
        return candidates.stream()
                .sorted(StandardVersionPreference.preferring(
                        Candidate::lifecycle, Candidate::indexedAt))
                .toList();
    }

    @Nested
    @DisplayName("lifecycle decides")
    class Lifecycle {

        @Test
        @DisplayName("ACTIVE is preferred over DRAFT, DEPRECATED and ARCHIVED")
        void activeWins() {
            var order = sorted(List.of(
                    new Candidate("v9", "ARCHIVED", 500),
                    new Candidate("v1", "DRAFT", 400),
                    new Candidate("v5", "ACTIVE", 100),
                    new Candidate("v7", "DEPRECATED", 300)));

            assertThat(order.getFirst().label()).isEqualTo("v5");
        }

        @Test
        @DisplayName("DRAFT is never a default, even when it is the newest")
        void draftIsNotDefault() {
            // A draft is not in force. Comparing a live process against it would measure compliance
            // with a rule nobody has adopted — and the reviewer would have no signal that happened.
            assertThat(StandardVersionPreference.canBeDefault("DRAFT")).isFalse();
            assertThat(StandardVersionPreference.canBeDefault("ARCHIVED")).isFalse();
            assertThat(StandardVersionPreference.canBeDefault("ACTIVE")).isTrue();
            assertThat(StandardVersionPreference.canBeDefault("DEPRECATED")).isTrue();
        }

        @Test
        @DisplayName("an unknown or missing lifecycle ranks LAST, not first")
        void unknownLifecycleRanksLast() {
            // A version whose lifecycle was never recorded must not silently become the default: that
            // would let a metadata gap decide what a document is measured against.
            assertThat(StandardVersionPreference.rank(null))
                    .isGreaterThan(StandardVersionPreference.rank("ARCHIVED"));
            assertThat(StandardVersionPreference.rank("WHATEVER"))
                    .isGreaterThan(StandardVersionPreference.rank("ARCHIVED"));
            assertThat(StandardVersionPreference.canBeDefault(null)).isFalse();
        }

        @Test
        @DisplayName("lifecycle matching ignores case and whitespace")
        void lifecycleIsLenient() {
            assertThat(StandardVersionPreference.rank("  active "))
                    .isEqualTo(StandardVersionPreference.rank("ACTIVE"));
        }
    }

    @Nested
    @DisplayName("version labels are NEVER used for ordering")
    class NoLexicalOrdering {

        @Test
        @DisplayName("v10 does not lose to v9")
        void numericSuffixesAreNotCompared() {
            // Lexically "v10" < "v9". If labels were sorted, v9 would win and the newer version would
            // be demoted to a rejected candidate.
            var order = sorted(List.of(
                    new Candidate("v9", "ACTIVE", 100),
                    new Candidate("v10", "ACTIVE", 200)));

            assertThat(order.getFirst().label())
                    .as("recency decides the tie, not the label")
                    .isEqualTo("v10");
        }

        @Test
        @DisplayName("the real corpus case: vv1 does not beat v3")
        void realCorpusLabels() {
            // "Change Management Standard" genuinely has v2, v3 and vv1, all ACTIVE. Lexical ascending
            // order would pick "v2"; lexical descending would pick "vv1". Both are wrong for reasons
            // that have nothing to do with which rules are in force.
            var order = sorted(List.of(
                    new Candidate("vv1", "ACTIVE", 100),
                    new Candidate("v2", "ACTIVE", 200),
                    new Candidate("v3", "ACTIVE", 300)));

            assertThat(order.getFirst().label()).isEqualTo("v3");
        }

        @Test
        @DisplayName("a non-numeric label does not break selection")
        void nonNumericLabels() {
            // "2026-Q1" has no comparable relationship to "3.0" at all. Recency still resolves it.
            var order = sorted(List.of(
                    new Candidate("2026-Q1", "ACTIVE", 100),
                    new Candidate("3.0", "ACTIVE", 900)));

            assertThat(order.getFirst().label()).isEqualTo("3.0");
        }
    }

    @Nested
    @DisplayName("superseded detection")
    class Superseded {

        @Test
        @DisplayName("DEPRECATED and ARCHIVED are superseded; ACTIVE and DRAFT are not")
        void supersededStates() {
            assertThat(StandardVersionPreference.isSuperseded("DEPRECATED")).isTrue();
            assertThat(StandardVersionPreference.isSuperseded("ARCHIVED")).isTrue();
            assertThat(StandardVersionPreference.isSuperseded("ACTIVE")).isFalse();
            // A draft is not superseded — it was never in force to begin with. The distinction matters
            // for supersededCandidateRate, which measures offering OUTDATED rules, not future ones.
            assertThat(StandardVersionPreference.isSuperseded("DRAFT")).isFalse();
            assertThat(StandardVersionPreference.isSuperseded(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("tie-breaking within one lifecycle")
    class TieBreak {

        @Test
        @DisplayName("the most recently indexed wins")
        void recencyBreaksTies() {
            var order = sorted(List.of(
                    new Candidate("a", "ACTIVE", 100),
                    new Candidate("b", "ACTIVE", 300),
                    new Candidate("c", "ACTIVE", 200)));

            assertThat(order.stream().map(Candidate::label)).containsExactly("b", "c", "a");
        }

        @Test
        @DisplayName("a version with no index time loses the tie-break")
        void missingTimestampLosesTie() {
            // 0 for an unrecorded timestamp. A version with no recorded index time should not outrank
            // one that has one.
            var order = sorted(List.of(
                    new Candidate("unknown-time", "ACTIVE", 0),
                    new Candidate("known-time", "ACTIVE", 50)));

            assertThat(order.getFirst().label()).isEqualTo("known-time");
        }
    }
}
