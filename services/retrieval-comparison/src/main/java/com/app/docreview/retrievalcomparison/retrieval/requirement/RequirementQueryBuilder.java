package com.app.docreview.retrievalcomparison.retrieval.requirement;

import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the retrieval query for one requirement. Architecture §15.2.
 *
 * <p>Not just the requirement text. §15.2 lists six inputs, and each contributes a different kind of
 * match:
 *
 * <ul>
 *   <li>The normalized requirement gives the core semantics.</li>
 *   <li>Clause fields (subject, action, object, timing) surface documents that use the same concepts
 *       with different sentence structure — "release manager authorizes" vs "approved by an
 *       independent reviewer".</li>
 *   <li>Expected-evidence hints describe what SATISFYING text looks like, which is often worded
 *       nothing like the requirement. A standard says "approval must be documented"; a process says
 *       "the ticket records the approver and timestamp". Embedding only the requirement misses that.</li>
 * </ul>
 *
 * <p>Terms are de-duplicated and the query is length-bounded: an over-long query dilutes its own
 * embedding, and every additional near-duplicate phrase pulls the vector toward the average of the
 * document rather than the specific obligation.
 */
@Component
public class RequirementQueryBuilder {

    /**
     * Character budget. Embedding models truncate silently past their context, so a query that
     * exceeds it loses its tail without any error — and the tail is where the evidence hints are.
     */
    private static final int MAX_QUERY_CHARS = 1200;

    public String build(ExtractedRequirement requirement) {
        // Insertion-ordered and de-duplicated: the requirement text and its clauses overlap heavily,
        // and repeating a phrase does not make it more retrievable.
        Set<String> parts = new LinkedHashSet<>();

        if (notBlank(requirement.normalizedRequirement())) {
            parts.add(requirement.normalizedRequirement());
        }
        if (notBlank(requirement.title())) {
            parts.add(requirement.title());
        }

        if (requirement.atomicClauses() != null) {
            for (AtomicClause clause : requirement.atomicClauses()) {
                // Assembled as a phrase rather than added as separate tokens: "reviewer approve
                // change before deployment" embeds closer to real prose than four isolated words.
                List<String> clauseParts = new ArrayList<>();
                addIfPresent(clauseParts, clause.subject());
                addIfPresent(clauseParts, clause.action());
                addIfPresent(clauseParts, clause.object());
                addIfPresent(clauseParts, clause.timing());
                addIfPresent(clauseParts, clause.condition());
                if (!clauseParts.isEmpty()) {
                    parts.add(String.join(" ", clauseParts));
                }
            }
        }

        if (requirement.expectedEvidenceHints() != null) {
            requirement.expectedEvidenceHints().stream()
                    .filter(this::notBlank)
                    .forEach(parts::add);
        }

        String query = String.join(". ", parts);
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }

    private void addIfPresent(List<String> target, String value) {
        if (notBlank(value)) {
            target.add(value);
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
