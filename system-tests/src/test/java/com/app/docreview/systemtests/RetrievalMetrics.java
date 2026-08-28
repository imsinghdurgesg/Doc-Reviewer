package com.app.docreview.systemtests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval quality, measured. Architecture §15.1.
 *
 * <p>These numbers exist to answer one question before a large change is made: <em>how badly does
 * standard selection actually fail at real corpus size?</em> With three standards it cannot fail; the
 * decision to rebuild retrieval should rest on a measurement at three hundred, not on anticipation.
 *
 * <p><strong>Every definition here is a choice, and a wrong one flatters the system.</strong> The
 * reasoning is recorded per metric for that reason.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * One case's outcome.
     *
     * @param ranked the standard keys returned, BEST FIRST. Includes candidates the service rejected,
     *               because a standard ranked third and then excluded by a threshold is a different
     *               failure from one never retrieved — the first is a tuning problem, the second is a
     *               retrieval problem, and collapsing them hides which.
     * @param selected the keys the service actually chose to compare against
     */
    public record CaseResult(
            RetrievalEvaluationSet.Case testCase,
            List<String> ranked,
            List<String> selected,
            List<String> selectedDepartments,
            /**
             * The {@code standardVersionId} of each selected candidate, in the same order as
             * {@code selected}.
             *
             * <p>Separate from {@code selected} because that holds standard KEYS: several versions of
             * one standard share a key, so keys alone cannot show that the wrong version was chosen —
             * which is the failure {@code wrongVersionRate} exists to catch.
             */
            List<String> selectedVersionIds,
            /**
             * The lifecycle of each selected candidate, in the same order as {@code selected}.
             *
             * <p>DRAFT, ACTIVE, DEPRECATED, or ARCHIVED. Read from the candidate payload rather than
             * inferred, since {@code supersededCandidateRate} is precisely a question about lifecycle.
             */
            List<String> selectedLifecycles,
            long latencyMs) {

        /** The convenience shape for cases that do not exercise version-level metrics. */
        public static CaseResult of(RetrievalEvaluationSet.Case testCase, List<String> ranked,
                                    List<String> selected, List<String> selectedDepartments,
                                    long latencyMs) {
            return new CaseResult(testCase, ranked, selected, selectedDepartments,
                    List.of(), List.of(), latencyMs);
        }
    }

    /**
     * Whether any expected standard appears in the top {@code k} of the ranking.
     *
     * <p>ANY, not all: a document may be governed by four standards, and asking whether all four are
     * in the top 1 is not a meaningful question. Whether at least one correct standard surfaces early
     * is what determines if the reviewer sees something useful on the selection page.
     *
     * <p>A case expecting nothing is EXCLUDED from recall rather than counted as a hit. Including it
     * would let a system that retrieves nothing for everything score 100% recall.
     */
    public static double recallAt(List<CaseResult> results, int k) {
        var scored = results.stream().filter(r -> !r.testCase().expectsNothing()).toList();
        if (scored.isEmpty()) {
            return Double.NaN;
        }
        long hits = scored.stream().filter(r -> {
            var topK = r.ranked().stream().limit(k).map(String::toLowerCase).toList();
            return r.testCase().expectedStandardKeys().stream()
                    .anyMatch(expected -> topK.contains(expected.toLowerCase()));
        }).count();
        return (double) hits / scored.size();
    }

    /**
     * The fraction of expected standards found, averaged over cases expecting more than one.
     *
     * <p>Measured separately from Recall@k because it answers the question that matters for
     * one-to-many assessment: when four standards apply, how many does retrieval actually surface?
     * Recall@5 can be 100% while this is 25%, and the difference is a review that looks complete and
     * is not.
     */
    public static double multiStandardRecall(List<CaseResult> results, int k) {
        var multi = results.stream()
                .filter(r -> !r.testCase().expectsNothing())
                .filter(r -> r.testCase().expectedStandardKeys().size() > 1)
                .toList();
        if (multi.isEmpty()) {
            return Double.NaN;
        }
        double total = 0;
        for (var result : multi) {
            var topK = result.ranked().stream().limit(k).map(String::toLowerCase).toList();
            long found = result.testCase().expectedStandardKeys().stream()
                    .filter(expected -> topK.contains(expected.toLowerCase()))
                    .count();
            total += (double) found / result.testCase().expectedStandardKeys().size();
        }
        return total / multi.size();
    }

    /**
     * How much of the top {@code k} is actually relevant.
     *
     * <p>The counterweight to recall. Retrieving twenty candidates guarantees high recall and makes
     * the selection page useless, so both must be reported or the harness rewards the wrong thing.
     */
    public static double precisionAt(List<CaseResult> results, int k) {
        var scored = results.stream().filter(r -> !r.testCase().expectsNothing()).toList();
        if (scored.isEmpty()) {
            return Double.NaN;
        }
        double total = 0;
        for (var result : scored) {
            var topK = result.ranked().stream().limit(k).map(String::toLowerCase).toList();
            if (topK.isEmpty()) {
                continue;
            }
            var expected = result.testCase().expectedStandardKeys().stream()
                    .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
            long relevant = topK.stream().filter(expected::contains).count();
            total += (double) relevant / topK.size();
        }
        return total / scored.size();
    }

    /**
     * How often a SELECTED standard belongs to a different department than the document.
     *
     * <p>The metric this whole exercise is motivated by. At corpus scale a change-management standard
     * from another department scores respectably against any change procedure, and selecting it
     * produces findings measured against a policy that does not govern the document.
     *
     * <p>Measured on SELECTED rather than ranked: a wrong-department candidate the reviewer can see and
     * decline is visible; one the system silently compared against is not.
     */
    public static double wrongDepartmentRate(List<CaseResult> results) {
        var scored = results.stream()
                .filter(r -> r.testCase().expectedDepartment() != null)
                .filter(r -> !r.selectedDepartments().isEmpty())
                .toList();
        if (scored.isEmpty()) {
            return Double.NaN;
        }
        long wrong = scored.stream().filter(r -> r.selectedDepartments().stream()
                .anyMatch(dept -> dept != null && !dept.isBlank()
                        && !dept.equalsIgnoreCase(r.testCase().expectedDepartment()))).count();
        return (double) wrong / scored.size();
    }

    /**
     * How often the RIGHT standard was selected at the WRONG version. Architecture §8.1, §19.2.
     *
     * <p><strong>Why this is separate from wrong-standard.</strong> Picking the wrong version of the
     * right standard is the more dangerous failure of the two, because it is invisible in review: the
     * subject matter matches, every finding reads plausibly, and the document has been measured against
     * rules that are not in force. Wrong-standard produces obviously irrelevant findings a reviewer
     * questions immediately; wrong-version produces credible ones nobody questions.
     *
     * <p>The corpus makes this concrete: one standard carries versions labelled {@code v2}, {@code v3},
     * and {@code vv1}, all ACTIVE. Any lexical ordering of those labels picks a wrong one, and the
     * ranking would look entirely healthy.
     *
     * <p><strong>Measured only on cases that name an expected version.</strong> A case labelling just
     * the standard makes a weaker claim, and counting it here would either invent a stricter expectation
     * or dilute the rate with cases that cannot fail. Skipped cases are excluded, not scored as passes.
     *
     * <p>Numerator counts cases where the standard was found but no expected version was among those
     * selected. A case that missed the standard entirely is a RECALL failure and is excluded — charging
     * it here would double-count one error under two metrics and make both look worse than they are.
     */
    public static double wrongVersionRate(List<CaseResult> results) {
        var scored = results.stream()
                .filter(r -> !r.testCase().expectsNothing())
                .filter(r -> r.testCase().expectsSpecificVersions())
                // The standard itself had to be selected. Otherwise this is a recall miss, measured
                // by recallAt, and attributing it to versioning would be wrong.
                .filter(r -> !lower(r.selected()).isEmpty())
                .filter(r -> r.testCase().expectedStandardKeys().stream()
                        .anyMatch(key -> lower(r.selected()).contains(key.toLowerCase())))
                .toList();
        if (scored.isEmpty()) {
            return Double.NaN;
        }
        long wrong = scored.stream().filter(r -> {
            var expected = lower(r.testCase().expectedStandardVersionIds());
            var actual = lower(r.selectedVersionIds());
            // Wrong when NONE of the expected versions was selected. Any overlap counts as correct:
            // selecting the right version alongside another is a precision question, not a version one.
            return actual.stream().noneMatch(expected::contains);
        }).count();
        return (double) wrong / scored.size();
    }

    /**
     * How often a SUPERSEDED version was among those selected. Architecture §8.1.
     *
     * <p>Superseded means DEPRECATED or ARCHIVED — a version the organization has replaced or retired.
     * DRAFT is deliberately NOT counted: a draft was never in force, so offering one is a
     * different-in-kind error (measuring against rules not yet adopted) rather than measuring against
     * outdated ones. Conflating them would hide which of the two is happening.
     *
     * <p>Distinct from {@link #wrongVersionRate}: that asks "was the expected version chosen?" and needs
     * a labelled expectation, whereas this asks "was something stale chosen?" and needs only the
     * lifecycle already on the candidate. So it reports on every case, including negatives, and is the
     * metric that still works before a labelled set exists.
     *
     * <p>Measured on SELECTED, not ranked. A superseded candidate a reviewer can see and decline is
     * visible; one the system compared against silently is not.
     */
    public static double supersededCandidateRate(List<CaseResult> results) {
        var scored = results.stream()
                .filter(r -> r.selectedLifecycles() != null && !r.selectedLifecycles().isEmpty())
                .toList();
        if (scored.isEmpty()) {
            return Double.NaN;
        }
        long stale = scored.stream()
                .filter(r -> r.selectedLifecycles().stream().anyMatch(RetrievalMetrics::isSuperseded))
                .count();
        return (double) stale / scored.size();
    }

    /**
     * True for DEPRECATED and ARCHIVED.
     *
     * <p>Duplicated from the worker's {@code StandardVersionPreference} rather than imported: §6 forbids
     * the system-tests module depending on a service jar, and the two definitions are pinned to agree by
     * {@code supersededMatchesServiceDefinition} in the metric tests.
     */
    private static boolean isSuperseded(String lifecycle) {
        if (lifecycle == null || lifecycle.isBlank()) {
            return false;
        }
        String normalised = lifecycle.trim().toUpperCase(java.util.Locale.ROOT);
        return normalised.equals("DEPRECATED") || normalised.equals("ARCHIVED");
    }

    /**
     * How often the system correctly retrieves nothing for a document the corpus does not cover.
     *
     * <p>Reported separately because it is the honesty metric. A system that always returns its best
     * guess scores well everywhere else while being unable to say "no standard here applies" — and a
     * comparison against an unrelated policy reports every requirement as a gap.
     */
    public static double noRelevantStandardAccuracy(List<CaseResult> results) {
        var negatives = results.stream()
                .filter(r -> r.testCase().expectsNothing()).toList();
        if (negatives.isEmpty()) {
            return Double.NaN;
        }
        long correct = negatives.stream().filter(r -> r.selected().isEmpty()).count();
        return (double) correct / negatives.size();
    }

    /** The 50th and 95th percentile latency. p95 rather than mean: the tail is what a user notices. */
    public static Map<String, Long> latency(List<CaseResult> results) {
        var sorted = results.stream().map(CaseResult::latencyMs).sorted().toList();
        var out = new LinkedHashMap<String, Long>();
        if (sorted.isEmpty()) {
            return out;
        }
        out.put("p50", sorted.get(Math.min(sorted.size() - 1, sorted.size() / 2)));
        out.put("p95", sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95))));
        out.put("max", sorted.get(sorted.size() - 1));
        return out;
    }

    /** A single report, formatted so two runs can be diffed. */
    public static String report(List<CaseResult> results) {
        var out = new StringBuilder();
        out.append("\n=== Retrieval evaluation ===\n");
        out.append(String.format("  cases                        %d%n", results.size()));
        long negatives = results.stream().filter(r -> r.testCase().expectsNothing()).count();
        out.append(String.format("    expecting a standard       %d%n", results.size() - negatives));
        out.append(String.format("    expecting nothing          %d%n", negatives));
        out.append("\n");
        for (int k : new int[]{1, 5, 10}) {
            out.append(String.format("  Recall@%-2d                    %s%n", k,
                    percent(recallAt(results, k))));
        }
        out.append(String.format("  Precision@5                  %s%n", percent(precisionAt(results, 5))));
        out.append(String.format("  Multi-standard recall@5      %s%n",
                percent(multiStandardRecall(results, 5))));
        out.append(String.format("  Wrong-department rate        %s%n",
                percent(wrongDepartmentRate(results))));
        // Version-level failures. Reported next to wrong-department because all three are "right
        // subject, wrong target" errors, which is the class a reviewer is least likely to catch.
        out.append(String.format("  Wrong-version rate           %s%n",
                percent(wrongVersionRate(results))));
        out.append(String.format("  Superseded-candidate rate    %s%n",
                percent(supersededCandidateRate(results))));
        out.append(String.format("  No-relevant accuracy        %s%n",
                percent(noRelevantStandardAccuracy(results))));
        var latency = latency(results);
        out.append(String.format("  Latency p50/p95/max         %s / %s / %s ms%n",
                latency.get("p50"), latency.get("p95"), latency.get("max")));
        out.append("\n  Failures:\n");
        int shown = 0;
        for (var result : results) {
            boolean failed = result.testCase().expectsNothing()
                    ? !result.selected().isEmpty()
                    : recallAt(List.of(result), 5) == 0.0;
            if (failed && shown++ < 20) {
                out.append(String.format("    %s%n      expected %s%n      got      %s%n",
                        result.testCase().description(),
                        result.testCase().expectedStandardKeys(),
                        result.ranked().stream().limit(5).toList()));
            }
        }
        if (shown == 0) {
            out.append("    none\n");
        }
        return out.toString();
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "n/a (no applicable cases)"
                : String.format("%.1f%%", value * 100);
    }

    /**
     * Lower-cased set, tolerating a null list and null elements.
     *
     * <p>Null-tolerant because an evaluation case may omit {@code expectedStandardVersionIds} entirely
     * and a candidate payload may carry a null lifecycle. A metric that threw on incomplete labelling
     * would make the harness unusable exactly while the set is being built.
     */
    static Set<String> lower(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
    }
}
