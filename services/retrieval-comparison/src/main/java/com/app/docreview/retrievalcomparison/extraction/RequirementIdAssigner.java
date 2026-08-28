package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Assigns stable, unique requirement identifiers. Architecture §12.7, §12.8 rule 5.
 *
 * <p>§12.7 says preserve the standard's own control ID when it has one, and otherwise generate a
 * stable version-scoped identifier. The extraction model does neither reliably: asked to extract from
 * one candidate group it returns SEVERAL requirements and derives each ID from the same source
 * control number, so a section containing three obligations yields three requirements all called
 * "5.1". §12.8 rule 5 then rejects the duplicates and the whole set lands in NEEDS_REVIEW — which
 * §8.1 refuses to activate and §12.8 refuses to compare against. Two Markdown standards were blocked
 * this way.
 *
 * <p>The model also invents a different disambiguation scheme on every run — {@code 3.1-REQ-1},
 * {@code 4.3-1}, {@code 5.2a}, {@code 5.2.1} were all observed for the same shape of input. §19.2
 * wants an identifier a reviewer can cite months later, and one that changes per extraction is not
 * that.
 *
 * <p><strong>Suffixes are positional, not sequential-on-arrival.</strong> The ordinal comes from the
 * requirement's position within its candidate group, so re-extracting the same standard version
 * produces the same IDs. Numbering them in the order the model happened to emit would make the ID
 * depend on model output ordering — stable-looking but not actually reproducible.
 */
@Component
@Slf4j
public class RequirementIdAssigner {

    /**
     * Returns the requirements with unique IDs, preserving the model's ID where it does not collide.
     *
     * @param taken IDs already ACCEPTED in this requirement-set version. Read, never mutated: the
     *              validator adds an ID only once its requirement passes (§12.8), so reserving one
     *              here would permanently burn the natural ID of any requirement the validator then
     *              rejects — the next re-extraction would silently shift to a suffixed form.
     */
    public List<ExtractedRequirement> assign(List<ExtractedRequirement> requirements,
                                             RequirementCandidate candidate,
                                             Set<String> taken) {
        List<ExtractedRequirement> out = new ArrayList<>(requirements.size());
        // Local claims for this group, so two requirements in the SAME response cannot both take one
        // ID even though neither is in `taken` yet.
        Set<String> claimed = new java.util.HashSet<>(taken);

        for (int position = 0; position < requirements.size(); position++) {
            ExtractedRequirement r = requirements.get(position);
            String proposed = normalise(r.requirementId(), candidate, position);
            String unique = makeUnique(proposed, candidate, position, claimed);

            if (!unique.equals(r.requirementId())) {
                log.debug("Requirement id {} -> {} (candidate {}, position {})",
                        r.requirementId(), unique, candidate.candidateId(), position);
            }
            claimed.add(unique);
            out.add(unique.equals(r.requirementId()) ? r : withId(r, unique));
        }
        return out;
    }

    /**
     * Falls back to a version-scoped identifier when the model supplied no usable ID (§12.7).
     *
     * <p>Built from the candidate's own source control number when it has one, so a reviewer still
     * navigates by the standard's numbering. Only when the standard itself is unnumbered does an
     * AUTO- identifier appear, which §12.7 explicitly allows.
     */
    private String normalise(String modelId, RequirementCandidate candidate, int position) {
        if (modelId != null && !modelId.isBlank()) {
            return modelId.strip();
        }
        String source = candidate.sourceControlId();
        if (source != null && !source.isBlank()) {
            return source.strip();
        }
        // Section path keeps it readable and scoped; the position keeps it distinct within the group.
        String section = candidate.sectionPath() == null || candidate.sectionPath().isEmpty()
                ? "SEC"
                : candidate.sectionPath().get(candidate.sectionPath().size() - 1)
                        .replaceAll("[^A-Za-z0-9.]+", "-");
        return "AUTO-%s-%02d".formatted(trim(section, 24), position + 1);
    }

    /**
     * Appends a deterministic positional suffix when the proposed ID is already used.
     *
     * <p>The first requirement to claim an ID keeps it unsuffixed, which matters because that is
     * usually the one whose numbering matches the standard's own text. Later collisions become
     * {@code 5.1-2}, {@code 5.1-3} — derived from position within the candidate group, so the same
     * standard version re-extracted yields the same IDs (§12.9 requires re-extraction to be
     * comparable, which is impossible if IDs move).
     */
    private String makeUnique(String proposed, RequirementCandidate candidate, int position,
                              Set<String> taken) {
        if (!taken.contains(proposed)) {
            return proposed;
        }

        // Position within the group is the primary discriminator.
        String positional = "%s-%d".formatted(proposed, position + 1);
        if (!taken.contains(positional)) {
            return positional;
        }

        // Same ID proposed from a DIFFERENT candidate group at the same position: qualify with the
        // candidate so the two remain distinguishable and still reproducible.
        String qualified = "%s-%s".formatted(proposed, trim(candidate.candidateId(), 12));
        if (!taken.contains(qualified)) {
            return qualified;
        }

        // Exhausted the deterministic forms. Counting up is a last resort and is order-dependent, so
        // it is logged: if this fires regularly, the candidate detector is grouping badly.
        for (int n = 2; n < 100; n++) {
            String numbered = "%s-%s-%d".formatted(proposed, trim(candidate.candidateId(), 12), n);
            if (!taken.contains(numbered)) {
                log.warn("Requirement id {} needed an order-dependent suffix; candidate grouping may "
                        + "be producing overlapping requirements", proposed);
                return numbered;
            }
        }
        throw new IllegalStateException("Could not assign a unique requirement id for " + proposed);
    }

    /**
     * Rewrites the ID, and the clause IDs derived from it.
     *
     * <p>Clause IDs must stay prefixed by their parent (§12.8 rule 6 traceability, and the comparison
     * validator checks the relationship). Renaming the parent without the clauses would make every
     * clause look like it belongs to a different requirement.
     */
    private ExtractedRequirement withId(ExtractedRequirement r, String id) {
        List<AtomicClause> clauses = new ArrayList<>();
        var original = r.atomicClauses() == null ? List.<AtomicClause>of() : r.atomicClauses();

        for (int i = 0; i < original.size(); i++) {
            AtomicClause c = original.get(i);
            // Letter suffixes, matching §12.6's CHG-07-A / -B / -C example.
            String clauseId = "%s-%s".formatted(id, (char) ('A' + Math.min(i, 25)));
            clauses.add(new AtomicClause(clauseId, c.subject(), c.action(), c.object(),
                    c.timing(), c.condition(), c.negated()));
        }

        return new ExtractedRequirement(
                id, r.parentControlId(),
                // sourceControlId is the STANDARD's own number and must not be rewritten: it is how
                // a reviewer cross-references the printed document (§12.7).
                r.sourceControlId(),
                r.title(), r.sourceSpanIds(), r.normativeStrength(), r.normalizedRequirement(),
                clauses, r.applicability(), r.expectedEvidenceHints(), r.extractionConfidence());
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "x";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
