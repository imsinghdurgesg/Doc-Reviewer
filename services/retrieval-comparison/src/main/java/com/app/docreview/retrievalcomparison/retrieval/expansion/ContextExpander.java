package com.app.docreview.retrievalcomparison.retrieval.expansion;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds bounded structural context around a retrieved passage. Architecture §15.3.
 *
 * <p>A retrieved chunk read in isolation is frequently ambiguous. "The approver signs off in the
 * ticket" does not say whether the approver is independent; the sentence before it might. §15.3
 * therefore permits parent heading, adjacent paragraphs within the same section, list parents, and
 * table headers.
 *
 * <p><strong>Bounded is the operative word.</strong> "Only the required context is sent to the
 * model" (§15.3). Unbounded expansion collapses into sending the whole document, which §15.5
 * explicitly forbids and which reintroduces the problem requirement-level retrieval exists to solve:
 * the model reasoning over everything at once, with evidence it was never asked to consider.
 */
@Component
public class ContextExpander {

    /** Neighbours on each side. One is usually the qualifying sentence; three is a page. */
    private static final int NEIGHBOUR_SPANS = 1;

    /** Hard ceiling regardless of neighbour count. */
    private static final int MAX_CONTEXT_CHARS = 900;

    /**
     * @param allSpans   the document's spans in reading order
     * @param coreSpanIds the spans that actually matched; context is added AROUND these and never
     *                    replaces them, because these are the citable evidence
     * @return context text, or null when nothing useful could be added
     */
    public String expand(List<EvidenceSpan> allSpans, List<String> coreSpanIds) {
        if (allSpans == null || allSpans.isEmpty() || coreSpanIds == null || coreSpanIds.isEmpty()) {
            return null;
        }

        int firstIndex = -1;
        int lastIndex = -1;
        for (int i = 0; i < allSpans.size(); i++) {
            if (coreSpanIds.contains(allSpans.get(i).spanId())) {
                if (firstIndex < 0) {
                    firstIndex = i;
                }
                lastIndex = i;
            }
        }
        if (firstIndex < 0) {
            return null;
        }

        var core = allSpans.get(firstIndex);
        List<String> pieces = new ArrayList<>();

        // The parent heading first: it is the cheapest disambiguation available. "Emergency Changes"
        // above a passage changes how every sentence under it should be read.
        if (core.sectionPath() != null && !core.sectionPath().isEmpty()) {
            pieces.add("[" + String.join(" > ", core.sectionPath()) + "]");
        }

        for (int i = Math.max(0, firstIndex - NEIGHBOUR_SPANS); i < firstIndex; i++) {
            // Same section only. A neighbour from a different section is adjacent in the file but
            // unrelated in meaning, and including it invites the model to read across a boundary
            // the document author drew deliberately.
            if (sameSection(allSpans.get(i), core)) {
                pieces.add(allSpans.get(i).exactText());
            }
        }
        for (int i = lastIndex + 1;
             i <= Math.min(allSpans.size() - 1, lastIndex + NEIGHBOUR_SPANS); i++) {
            if (sameSection(allSpans.get(i), core)) {
                pieces.add(allSpans.get(i).exactText());
            }
        }

        if (pieces.isEmpty()) {
            return null;
        }
        String context = String.join(" ", pieces);
        return context.length() <= MAX_CONTEXT_CHARS
                ? context
                : context.substring(0, MAX_CONTEXT_CHARS);
    }

    private boolean sameSection(EvidenceSpan a, EvidenceSpan b) {
        return a.sectionPath() != null && a.sectionPath().equals(b.sectionPath());
    }
}
