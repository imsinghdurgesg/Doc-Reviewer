package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.extraction.CandidateCategory;
import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic requirement identifiers. Architecture §12.7, §12.8 rule 5, §12.9.
 *
 * <p>Two properties matter and are easy to get wrong together: IDs must be UNIQUE within a set (or
 * rule 5 rejects requirements and the set never publishes), and they must be REPRODUCIBLE across
 * re-extractions (or §12.9's "re-extraction creates a comparable new version" is false and stored
 * findings cite identifiers that have moved).
 */
class RequirementIdAssignerTest {

    private final RequirementIdAssigner assigner = new RequirementIdAssigner();

    private RequirementCandidate candidate(String id, String sourceControlId, String section) {
        return new RequirementCandidate(id, CandidateCategory.MANDATORY_REQUIREMENT,
                List.of("SPAN-1"), List.of(section), "text", List.of("must"), sourceControlId);
    }

    private ExtractedRequirement requirement(String id, String... clauseIds) {
        List<AtomicClause> clauses = java.util.Arrays.stream(clauseIds)
                .map(c -> new AtomicClause(c, "subject", "action", "object", null, null, false))
                .toList();
        return new ExtractedRequirement(id, "5", "5.1", "title", List.of("SPAN-1"),
                NormativeStrength.MANDATORY, "normalized", clauses, null, List.of(), 0.9);
    }

    @Nested
    @DisplayName("uniqueness (§12.8 rule 5)")
    class Uniqueness {

        @Test
        @DisplayName("duplicates within one candidate group are suffixed, not rejected")
        void suffixesDuplicatesInGroup() {
            // The real failure: a section with three obligations comes back as three requirements all
            // called "5.1", and rule 5 rejected two — sending the whole set to NEEDS_REVIEW over a
            // naming collision rather than a bad extraction.
            var result = assigner.assign(
                    List.of(requirement("5.1"), requirement("5.1"), requirement("5.1")),
                    candidate("CAND-5", "5", "5 Disposal"), new HashSet<>());

            assertThat(result).extracting(ExtractedRequirement::requirementId)
                    .doesNotHaveDuplicates()
                    .hasSize(3);
            // The FIRST claimant keeps the unsuffixed form: it is the one whose numbering matches the
            // standard's own text, which is what a reviewer cites.
            assertThat(result.get(0).requirementId()).isEqualTo("5.1");
        }

        @Test
        @DisplayName("collisions with already-accepted IDs are avoided")
        void avoidsAcceptedIds() {
            Set<String> taken = new HashSet<>(Set.of("5.1"));

            var result = assigner.assign(List.of(requirement("5.1")),
                    candidate("CAND-9", "5", "5 Disposal"), taken);

            assertThat(result.get(0).requirementId()).isNotEqualTo("5.1");
        }

        @Test
        @DisplayName("the accepted-id set is NOT mutated")
        void doesNotReserveIds() {
            Set<String> taken = new HashSet<>();

            assigner.assign(List.of(requirement("5.1"), requirement("5.1")),
                    candidate("CAND-5", "5", "5 Disposal"), taken);

            // The validator adds an ID only once its requirement passes (§12.8). Reserving here would
            // burn the natural ID of anything the validator then rejects, and the next extraction
            // would silently shift to a suffixed form.
            assertThat(taken).isEmpty();
        }
    }

    @Nested
    @DisplayName("reproducibility (§12.9)")
    class Reproducibility {

        @Test
        @DisplayName("the same input yields the same IDs on a second run")
        void stableAcrossRuns() {
            var input = List.of(requirement("5.1"), requirement("5.1"), requirement("5.2"));
            var c = candidate("CAND-5", "5", "5 Disposal");

            var first = assigner.assign(input, c, new HashSet<>());
            var second = assigner.assign(input, c, new HashSet<>());

            assertThat(first).extracting(ExtractedRequirement::requirementId)
                    .isEqualTo(second.stream().map(ExtractedRequirement::requirementId).toList());
        }

        @Test
        @DisplayName("suffixes come from POSITION, so they do not depend on arrival order")
        void positionalNotSequential() {
            // Position-derived: the second requirement in the group gets -2 regardless of how many
            // requirements happened to be emitted before it in some other group.
            var result = assigner.assign(
                    List.of(requirement("5.1"), requirement("5.1")),
                    candidate("CAND-5", "5", "5 Disposal"), new HashSet<>());

            assertThat(result.get(1).requirementId()).isEqualTo("5.1-2");
        }
    }

    @Nested
    @DisplayName("§12.7 fallback and traceability")
    class FallbackAndTraceability {

        @Test
        @DisplayName("a blank model ID falls back to the standard's own control number")
        void fallsBackToSourceControlId() {
            var result = assigner.assign(List.of(requirement("")),
                    candidate("CAND-3", "3.2", "3 Retention"), new HashSet<>());

            // §12.7: preserve the standard's numbering where it exists, so a reviewer can still
            // cross-reference the printed document.
            assertThat(result.get(0).requirementId()).isEqualTo("3.2");
        }

        @Test
        @DisplayName("an unnumbered standard gets an AUTO- identifier")
        void generatesAutoId() {
            var result = assigner.assign(List.of(requirement(null)),
                    candidate("CAND-7", null, "Access Review"), new HashSet<>());

            assertThat(result.get(0).requirementId()).startsWith("AUTO-");
        }

        @Test
        @DisplayName("clause IDs are re-prefixed when the parent ID changes (§12.8 rule 6)")
        void clausesFollowTheParent() {
            var result = assigner.assign(
                    List.of(requirement("5.1", "5.1-A"), requirement("5.1", "5.1-A", "5.1-B")),
                    candidate("CAND-5", "5", "5 Disposal"), new HashSet<>());

            var renamed = result.get(1);
            assertThat(renamed.requirementId()).isEqualTo("5.1-2");
            // A clause still prefixed with the OLD parent would look like it belongs to a different
            // requirement, and the comparison validator checks that relationship.
            assertThat(renamed.atomicClauses())
                    .extracting(AtomicClause::clauseId)
                    .containsExactly("5.1-2-A", "5.1-2-B");
        }

        @Test
        @DisplayName("sourceControlId is never rewritten")
        void preservesSourceControlId() {
            var result = assigner.assign(
                    List.of(requirement("5.1"), requirement("5.1")),
                    candidate("CAND-5", "5", "5 Disposal"), new HashSet<>());

            // The standard's own number is how a reviewer cross-references the document; only the
            // internal identifier is disambiguated.
            assertThat(result).allSatisfy(r -> assertThat(r.sourceControlId()).isEqualTo("5.1"));
        }
    }
}
