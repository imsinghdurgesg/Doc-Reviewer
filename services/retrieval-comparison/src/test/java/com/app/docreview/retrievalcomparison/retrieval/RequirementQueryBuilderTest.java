package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.retrieval.requirement.RequirementQueryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement query construction. Architecture §15.2. */
class RequirementQueryBuilderTest {

    private final RequirementQueryBuilder builder = new RequirementQueryBuilder();

    private ExtractedRequirement requirement(List<AtomicClause> clauses, List<String> hints) {
        return new ExtractedRequirement("CHG-04", "CHG", "4.2",
                "Independent pre-deployment approval", List.of("SPAN-1"),
                NormativeStrength.MANDATORY,
                "A person independent of the implementer must approve the change before deployment.",
                clauses, null, hints, 0.95);
    }

    @Test
    @DisplayName("clause fields are included, so differently-worded processes still match")
    void includesClauseFields() {
        // The retrieval case that matters: the standard says "independent reviewer approves", the
        // process says "release manager who did not implement the change authorizes". Embedding only
        // the requirement sentence misses it.
        var query = builder.build(requirement(
                List.of(new AtomicClause("A", "independent reviewer", "approve", "change",
                        "before deployment", null, false)),
                List.of()));

        assertThat(query).contains("independent reviewer", "approve", "before deployment");
    }

    @Test
    @DisplayName("evidence hints are included: satisfying text rarely echoes the requirement")
    void includesEvidenceHints() {
        var query = builder.build(requirement(
                List.of(new AtomicClause("A", "reviewer", "approve", "change", null, null, false)),
                List.of("approval timestamp", "independence from implementer")));

        assertThat(query).contains("approval timestamp", "independence from implementer");
    }

    @Test
    @DisplayName("duplicate phrases are collapsed")
    void deduplicates() {
        // The title and the normalized requirement overlap heavily; repeating a phrase does not make
        // it more retrievable, it just dilutes the vector.
        var r = new ExtractedRequirement("R", null, null, "Approve the change",
                List.of("SPAN-1"), NormativeStrength.MANDATORY, "Approve the change",
                List.of(new AtomicClause("A", null, "Approve the change", null, null, null, false)),
                null, List.of("Approve the change"), 0.9);

        long occurrences = builder.build(r).split("Approve the change", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    @DisplayName("the query is length-bounded, because models truncate silently")
    void boundsLength() {
        // Past the model's context the tail is dropped with no error — and the tail is where the
        // evidence hints are.
        var manyHints = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "a fairly long expected evidence hint number " + i)
                .toList();
        var query = builder.build(requirement(
                List.of(new AtomicClause("A", "x", "y", "z", null, null, false)), manyHints));

        assertThat(query.length()).isLessThanOrEqualTo(1200);
    }

    @Test
    @DisplayName("null clause fields are skipped without producing empty separators")
    void handlesSparseClauses() {
        var query = builder.build(requirement(
                List.of(new AtomicClause("A", null, "retain evidence", null, "seven years", null, false)),
                List.of()));

        assertThat(query).contains("retain evidence", "seven years").doesNotContain("null");
    }
}
