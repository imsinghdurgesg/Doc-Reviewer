package com.app.docreview.retrievalcomparison.retrieval.document;

import java.util.Comparator;
import java.util.Locale;

/**
 * Chooses which VERSION of a standard is the default candidate. Architecture §8.1, §15.1, §19.2.
 *
 * <p><strong>Lifecycle decides, never the version string.</strong> Comparing version labels lexically is
 * wrong in ways that are easy to miss and produce a confidently incorrect assessment:
 * <ul>
 *   <li>{@code "v10"} sorts before {@code "v9"};</li>
 *   <li>{@code "vv1"} sorts before {@code "v2"} — and the live corpus contains exactly that pair;</li>
 *   <li>{@code "2.10"} sorts before {@code "2.9"};</li>
 *   <li>a label like {@code "2026-Q1"} has no comparable relationship to {@code "3.0"} at all.</li>
 * </ul>
 * Version labels are administrator-entered free text (§8.1); they are an identifier, not an ordering.
 *
 * <p>So the order of preference is what the organization actually asserts:
 * <ol>
 *   <li>{@code ACTIVE} — the version currently in force;</li>
 *   <li>{@code DEPRECATED} — superseded but still governing existing processes;</li>
 *   <li>{@code DRAFT} — not yet in force, so never a default;</li>
 *   <li>{@code ARCHIVED} — retained for reproducing historical comparisons only.</li>
 * </ol>
 *
 * <p>Ties within a lifecycle are broken by INDEX RECENCY, not by label. Two ACTIVE versions of one
 * standard is a data problem the platform cannot resolve on its own, and the most recently indexed is
 * the least-wrong guess — but the runner-up stays a visible candidate so a reviewer can override it.
 *
 * <p><strong>Non-default versions are never hidden.</strong> §15.1 requires that a standard considered
 * and not used remains visible with a reason. This selects a DEFAULT, and demotes the rest; it does not
 * filter them out.
 */
public final class StandardVersionPreference {

    private StandardVersionPreference() {
    }

    /**
     * Rank for a lifecycle value. Lower is more preferred.
     *
     * <p>An unrecognised or absent lifecycle ranks LAST rather than first. A version whose lifecycle
     * was never recorded must not silently become the default for its standard — that would let a
     * metadata gap decide what a document is measured against.
     */
    public static int rank(String lifecycle) {
        if (lifecycle == null || lifecycle.isBlank()) {
            return 99;
        }
        return switch (lifecycle.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> 0;
            case "DEPRECATED" -> 1;
            case "DRAFT" -> 2;
            case "ARCHIVED" -> 3;
            default -> 99;
        };
    }

    /** True when this lifecycle means the version has been superseded or retired. */
    public static boolean isSuperseded(String lifecycle) {
        if (lifecycle == null) {
            return false;
        }
        String normalised = lifecycle.trim().toUpperCase(Locale.ROOT);
        return normalised.equals("DEPRECATED") || normalised.equals("ARCHIVED");
    }

    /** True when this lifecycle is eligible to be a default candidate at all. */
    public static boolean canBeDefault(String lifecycle) {
        // DRAFT is excluded: it is not in force, so comparing a live process against it would measure
        // compliance with a rule nobody has adopted. ARCHIVED is excluded for the mirror reason.
        int rank = rank(lifecycle);
        return rank == 0 || rank == 1;
    }

    /**
     * Orders two versions of the SAME standard, most-preferred first.
     *
     * @param lifecycleOf  reads a candidate's lifecycle
     * @param indexedAtOf  reads a candidate's index timestamp as epoch millis; 0 when unknown
     */
    public static <T> Comparator<T> preferring(
            java.util.function.Function<T, String> lifecycleOf,
            java.util.function.ToLongFunction<T> indexedAtOf) {

        return Comparator
                .comparingInt((T candidate) -> rank(lifecycleOf.apply(candidate)))
                // Recency, NOT version label. See the class comment on why labels cannot be ordered.
                .thenComparing(Comparator.comparingLong(indexedAtOf).reversed());
    }
}
