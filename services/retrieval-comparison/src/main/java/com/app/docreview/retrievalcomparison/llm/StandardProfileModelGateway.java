package com.app.docreview.retrievalcomparison.llm;

import java.util.List;

/**
 * Derives descriptive metadata for a standard by reading it. Architecture §10.3, §15.1.
 *
 * <p><strong>Why this exists.</strong> The vector that decides which standard an uploaded document is
 * matched against is built from {@code title + description + category + tags}. For standards created
 * through the UI those come from a form; for standards synced from the bucket there is nowhere for
 * them to come from at all — the title was derived from a folder name and the description was a
 * hardcoded constant, IDENTICAL for every synced standard. Shared text does not merely fail to help:
 * it pulls the vectors of unrelated standards toward each other.
 *
 * <p>So the metadata is read out of the document instead. One call per standard VERSION at index
 * time — not per requirement, which is what requirement extraction does.
 *
 * <p><strong>Inferred, not authoritative.</strong> The result is a suggestion. It is recorded as
 * model-derived so an administrator can see what was guessed and correct it, because a
 * mis-categorised standard is hard to find and nothing else would explain why.
 *
 * <p>An interface so domain code never sees Spring AI (§10.3).
 */
public interface StandardProfileModelGateway {

    /**
     * Reads the opening of a standard and proposes metadata for it.
     *
     * @param title    the title already known, as context; the model may propose a better one
     * @param openingText the standard's first passages — title, purpose, scope. NOT the whole
     *                    document: what is wanted is what the standard is ABOUT, and forty pages of
     *                    clauses dilute that.
     * @return the proposal, or {@link Result#unavailable()} when the model could not be reached or
     *         returned nothing usable. Never throws: indexing must still complete, with a weaker
     *         profile, rather than failing a standard over optional metadata.
     */
    Result describe(String title, String openingText);

    /**
     * @param description one or two sentences on what the standard governs
     * @param category    subject area as the model worded it. FREE TEXT by decision: it feeds the
     *                    matching vector, where near-synonyms are harmless because the text is
     *                    embedded rather than compared exactly. It is NOT reliable for filtering —
     *                    the same document can come back "Change Control" one run and "Change
     *                    Management" the next.
     * @param tags        specific topics, lowercase, for the same vector
     * @param modelAlias  which model produced this, recorded for §19.2
     * @param available   false when the call failed; the caller then indexes without this metadata
     */
    record Result(
            String description,
            String category,
            List<String> tags,
            String modelAlias,
            boolean available) {

        public static Result unavailable() {
            return new Result(null, null, List.of(), null, false);
        }
    }
}
