package com.app.docreview.documentprocessing.canonical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Identifies running headers and footers. Architecture §11.2.
 *
 * <p>Why this matters more than it looks: a header repeated on 40 pages is 40 near-identical passages
 * in the retrieval index. Because it contains the document's own title, it is lexically similar to
 * almost every query about that document — so it ranks highly for everything and crowds out real
 * content. §11.2 requires such content to be marked so retrieval can exclude it by default.
 *
 * <p>Detection is by REPETITION plus POSITION, not by a lexical pattern. "Confidential" is a header
 * in one document and a defined term in another; only the fact that it appears at the top of every
 * page distinguishes them. A single-page document therefore has no repeated content by definition,
 * which is correct rather than a limitation.
 */
public final class RepeatedContentDetector {

    /** A line must appear on at least this share of pages to count as running content. */
    private static final double MIN_PAGE_FRACTION = 0.6;

    /** Below this many pages, repetition is not evidence of anything. */
    private static final int MIN_PAGES = 3;

    private RepeatedContentDetector() {
    }

    /**
     * @param linesByPage the text lines of each page, in order
     * @return the normalized line texts that qualify as repeated
     */
    public static Set<String> detect(List<List<String>> linesByPage) {
        int pageCount = linesByPage.size();
        if (pageCount < MIN_PAGES) {
            return Set.of();
        }

        // Count DISTINCT pages a line appears on, not total occurrences: a phrase used three times
        // on one page is emphasis, not a running header.
        Map<String, Integer> pagesContaining = new HashMap<>();
        for (List<String> page : linesByPage) {
            for (String line : new ArrayList<>(page.stream().map(RepeatedContentDetector::normalize)
                    .filter(l -> !l.isBlank())
                    .distinct()
                    .toList())) {
                pagesContaining.merge(line, 1, Integer::sum);
            }
        }

        int threshold = (int) Math.ceil(pageCount * MIN_PAGE_FRACTION);
        return pagesContaining.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Normalizes for comparison.
     *
     * <p>Page numbers are replaced with a placeholder so "Page 4 of 18" and "Page 5 of 18" are
     * recognized as the same footer. Without that, every footer looks unique and none is detected —
     * which is the most common form of this content.
     */
    public static String normalize(String line) {
        return line.replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
