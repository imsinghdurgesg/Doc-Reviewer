package com.app.docreview.reviewcore.standards.application;

import java.util.Locale;
import java.util.Set;

/**
 * Protects human-curated standard metadata from being overwritten. Architecture §8.1, §15.1.
 *
 * <p><strong>MongoDB is the source of truth for department, standardFamily, and documentType.</strong>
 * Qdrant payloads are a derived projection, rebuilt from Mongo on every indexing and re-embedding
 * operation. That direction is deliberate and one-way: a label edited in the vector store would be
 * silently discarded on the next re-embed, and a label inferred by a model must never become the record
 * an administrator then sees as authoritative.
 *
 * <p><strong>The overwrite rules.</strong> A curated value is replaced only by another curated value. In
 * particular a reprocessing run must never replace one with:
 * <ul>
 *   <li>{@code null} or blank — "the model did not answer" is not a correction;</li>
 *   <li>a placeholder such as {@code UNKNOWN}, {@code N/A}, or {@code TBD} — these are absence wearing
 *       the costume of a value, and they defeat the "is it already set?" check that guards curation;</li>
 *   <li>an inferred value, when a human value is present — the human read the document, the model read
 *       its first few pages.</li>
 * </ul>
 *
 * <p>Why this is a class rather than three inline null checks: the rule was previously spread across
 * call sites as {@code x != null && existing == null}, which silently permits {@code "UNKNOWN"} to be
 * written and then treated as curated forever afterwards. One place to state the rule is one place to
 * test it.
 */
public final class CuratedMetadata {

    /**
     * Strings that mean "no value" despite being non-blank.
     *
     * <p>Compared case-insensitively after trimming. Models emit these readily when asked to classify
     * something they cannot determine, and the closed-vocabulary prompt explicitly permits abstention —
     * so any of these reaching a write path is a bug being caught, not an unexpected input.
     */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "unknown", "n/a", "na", "none", "null", "tbd", "to be determined",
            "not applicable", "not specified", "unspecified", "undetermined", "-", "--");

    private CuratedMetadata() {
    }

    /**
     * True when {@code value} is a real value rather than absence or a placeholder.
     *
     * <p>Used on both sides of an overwrite decision: to ask whether the existing value deserves
     * protection, and whether the incoming value is worth writing.
     */
    public static boolean isMeaningful(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return !PLACEHOLDERS.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The value that should be stored, given what is already there and what a reprocessing run proposes.
     *
     * <p>Curation wins. An incoming value is written ONLY when nothing meaningful is stored yet, which
     * makes reprocessing idempotent with respect to curated fields: running it twice, or running it
     * after an administrator edits a label, leaves the administrator's value intact.
     *
     * @param existing  what MongoDB holds — the source of truth
     * @param incoming  what indexing or inference proposes
     * @return {@code existing} whenever it is meaningful; otherwise {@code incoming} when that is
     *         meaningful; otherwise {@code existing} unchanged, so a placeholder never lands
     */
    public static String preserve(String existing, String incoming) {
        if (isMeaningful(existing)) {
            return existing;
        }
        return isMeaningful(incoming) ? incoming.trim() : existing;
    }

    /**
     * True when writing {@code incoming} over {@code existing} would actually change the record.
     *
     * <p>Lets a caller skip a database write, and — more usefully — lets a test assert that a
     * reprocessing run over already-curated metadata performs no writes at all.
     */
    public static boolean wouldChange(String existing, String incoming) {
        String result = preserve(existing, incoming);
        return result != null && !result.equals(existing);
    }
}
