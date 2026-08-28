package com.app.docreview.systemtests;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The labelled set the retrieval evaluation measures against. Architecture §15.1.
 *
 * <p><strong>Why a file rather than fixtures in code.</strong> The labels are a judgement about which
 * standards genuinely govern a document, and only someone who knows the corpus can make it. Putting
 * them in a data file means the evaluation can be re-labelled without a code change, and that a
 * disagreement about a label is visible in a diff rather than buried in a test.
 *
 * <p><strong>Absence is a valid label.</strong> {@code expectedStandardKeys: []} asserts that NOTHING
 * should be retrieved — a document the corpus does not cover. That case matters as much as a positive
 * one: a system that always returns its best guess scores well on recall while being useless, because
 * it never says "no standard here applies". Selection is supposed to refuse below the relevance floor,
 * and only an empty-label case tests it.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record RetrievalEvaluationSet(List<Case> cases) {

    /**
     * @param documentVersionId  an uploaded process document already parsed in the target deployment
     * @param description        free text, so a failing case is identifiable without opening the file
     * @param expectedStandardKeys the standards a knowledgeable reviewer says apply. ORDER IS IGNORED —
     *                           ranking is measured by position in the RESULT, not in the label.
     * @param expectedDepartment the department the document belongs to, for the wrong-department rate.
     *                           Null when the corpus has no department for it.
     */
    /**
     * @param expectedStandardVersionIds the specific VERSION of each expected standard that should be
     *                                   selected — normally the one in force. Optional: a case can
     *                                   label the standard without committing to a version, and
     *                                   {@code wrongVersionRate} then skips that case rather than
     *                                   guessing. Labelling only the standard is a weaker but honest
     *                                   claim, and a metric must never invent the stricter one.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(
            String documentVersionId,
            String description,
            List<String> expectedStandardKeys,
            String expectedDepartment,
            List<String> expectedStandardVersionIds) {

        /** A document the corpus is not expected to cover. */
        public boolean expectsNothing() {
            return expectedStandardKeys == null || expectedStandardKeys.isEmpty();
        }

        /** True when this case commits to a specific version, making wrong-version measurable. */
        public boolean expectsSpecificVersions() {
            return expectedStandardVersionIds != null && !expectedStandardVersionIds.isEmpty();
        }
    }

    /**
     * Loads the set, or an empty one when the file is absent.
     *
     * <p>Empty rather than an error: the harness has to exist and compile before the corpus arrives,
     * and a missing file at that point means "not labelled yet", not "broken". The evaluation reports
     * that it was skipped, so an absent file cannot be mistaken for a pass.
     */
    public static RetrievalEvaluationSet load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new RetrievalEvaluationSet(List.of());
        }
        return new ObjectMapper().readValue(Files.readString(path), RetrievalEvaluationSet.class);
    }

    public boolean isEmpty() {
        return cases == null || cases.isEmpty();
    }
}
