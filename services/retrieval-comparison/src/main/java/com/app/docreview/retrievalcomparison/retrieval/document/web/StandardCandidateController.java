package com.app.docreview.retrievalcomparison.retrieval.document.web;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.retrieval.StandardCandidate;
import com.app.docreview.retrievalcomparison.llm.DocumentContextModelGateway;
import com.app.docreview.retrievalcomparison.retrieval.document.AssessmentContext;
import com.app.docreview.retrievalcomparison.retrieval.document.StandardSelectionService;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Candidate standards for a document, WITHOUT running a comparison. Architecture §15.1.
 *
 * <p><strong>Why this is separate from comparison.</strong> Selection was reachable only as a side
 * effect of starting an assessment, so there was no way to ask "which standards apply here?" — not for
 * a reviewer choosing, and not for measuring how well retrieval performs. A recommended-standards
 * screen needs the first; deciding whether retrieval must be rebuilt needs the second.
 *
 * <p><strong>Returns rejected candidates too.</strong> A standard ranked third and then dropped by a
 * threshold is a different failure from one never retrieved at all: the first is tuning, the second is
 * retrieval. Collapsing them hides which, and §15.1 already requires a reviewer to see that a standard
 * was considered and on what basis it was excluded.
 *
 * <p>Retrieval relevance is NOT compliance. A high score means "worth comparing against", never
 * "compliant with" — two passages can be near-identical and directly contradict each other.
 */
@RestController
@RequestMapping("/internal/v1/standard-candidates")
@RequiredArgsConstructor
@Slf4j
public class StandardCandidateController {

    /**
     * Passages used to characterise the document. Matches {@code ComparisonJobRunner} exactly, so the
     * candidates a reviewer is shown are the ones an assessment would have used — a preview computed
     * differently from the real thing is worse than none.
     */
    private static final int PROFILE_SPAN_LIMIT = 40;

    /**
     * Confidence at or above which an inference is presented as settled rather than as a question.
     *
     * <p>0.75 rather than 0.9: the model is choosing from a closed list of labels that exist on the
     * retrieved candidates, which is a much easier task than open classification, so mid-range
     * confidence is still usable. Below {@link #LOW_CONFIDENCE} the values are shown but flagged as a
     * guess worth checking.
     */
    private static final double HIGH_CONFIDENCE = 0.75;

    private static final double LOW_CONFIDENCE = 0.45;

    /**
     * How many candidates arrive pre-checked on the selection screen. Architecture §15.1, §15.5.
     *
     * <p>Independent of how many are SHOWN: the screen offers breadth so a reviewer can include a
     * lower-ranked standard, but preselects narrowly because every extra standard multiplies
     * per-requirement model calls and reports one gap once per standard. Three, per the design's
     * {@code maximum-preselected}.
     *
     * <p>Deliberately larger than {@code MAX_AUTO_SELECTED_STANDARDS} (1). Automatic selection
     * substitutes for a choice nobody made and must be conservative; here a reviewer sees and confirms
     * what was chosen.
     */
    private static final int MAX_PRESELECTED_STANDARDS = 3;

    private final StandardSelectionService selection;
    private final EvidenceSpanReader spanReader;
    private final DocumentContextModelGateway documentContext;

    /**
     * @param documentVersionId a parsed process document
     * @param limit             how many candidates to consider. Independent of how many an assessment
     *                          would SELECT: the screen shows more than it preselects.
     * @param department        inferred or reviewer-corrected. A RANKING signal only: filtering on an
     *                          inferred value would hide a standard family behind unverified metadata.
     * @param guidance          optional reviewer text, appended to the document profile so a stated
     *                          focus ("emergency change controls") can steer retrieval that the
     *                          document's own wording would not
     */
    @GetMapping("/{documentVersionId}")
    public CandidateResponse candidates(
            @PathVariable String documentVersionId,
            @RequestParam(defaultValue = "8") int limit,
            @RequestParam(defaultValue = "") String department,
            @RequestParam(defaultValue = "") String standardFamily,
            @RequestParam(defaultValue = "") String documentType,
            @RequestParam(defaultValue = "") String guidance) {

        List<EvidenceSpan> spans = spanReader.allForVersion(documentVersionId);
        if (spans.isEmpty()) {
            // Not an error: the document may still be parsing. An empty list with a reason beats a
            // 404 that a caller cannot distinguish from "no such document".
            return new CandidateResponse(documentVersionId, List.of(),
                    "This document has no evidence spans yet. Processing may still be running.",
                    InferredContext.none());
        }

        String profile = spans.stream()
                .map(EvidenceSpan::exactText)
                .filter(text -> text != null && !text.isBlank())
                .limit(PROFILE_SPAN_LIMIT)
                .collect(java.util.stream.Collectors.joining(" "));

        if (guidance != null && !guidance.isBlank()) {
            // Appended, not substituted: the reviewer's note narrows what to look for, it does not
            // replace what the document is about.
            profile = profile + " " + guidance.trim();
        }

        // Retrieve ONCE, then rank once. Inference needs to know which labels exist on the candidates
        // before it can choose among them, so the order is: retrieve -> harvest vocabulary -> infer ->
        // rank. Embedding and searching a second time would double the cost of every lookup.
        var hits = selection.retrieve(profile);

        // Reviewer-supplied values always win over inference: the reviewer is correcting the machine,
        // and a later inference overwriting a typed value would make the field feel broken. Inference
        // only fills what was left blank.
        var supplied = new AssessmentContext(blankToNull(department), blankToNull(standardFamily),
                blankToNull(documentType), blankToNull(guidance));

        var inferred = inferIfNeeded(supplied, profile, hits);

        var context = new AssessmentContext(
                firstNonBlank(supplied.department(), inferred.department()),
                firstNonBlank(supplied.standardFamily(), inferred.standardFamily()),
                firstNonBlank(supplied.documentType(), inferred.documentType()),
                supplied.guidance());

        // PRESELECTION is capped separately from how many candidates are SHOWN.
        //
        // These were the same number, so `limit=20` pre-checked all twenty and a reviewer who did not
        // notice would start a comparison against twenty standards — the duplicate-findings failure
        // that MAX_AUTO_SELECTED_STANDARDS exists to prevent, reintroduced through the UI. The screen
        // should offer breadth and preselect narrowly: the reviewer adds, rather than removes.
        int shown = Math.min(Math.max(limit, 1), 50);
        int preselected = Math.min(shown, MAX_PRESELECTED_STANDARDS);

        var candidates = selection.rank(hits, preselected, context);
        if (candidates.size() > shown) {
            candidates = candidates.subList(0, shown);
        }
        log.info("Candidate lookup for {} returned {} candidate(s), {} preselected",
                documentVersionId, candidates.size(),
                candidates.stream().filter(StandardCandidate::selected).count());

        return new CandidateResponse(documentVersionId, candidates, null,
                new InferredContext(
                        inferred.department(), inferred.standardFamily(), inferred.documentType(),
                        inferred.confidence(), tierOf(inferred)));
    }

    /**
     * Infers the context, unless the reviewer already supplied all of it.
     *
     * <p>Skipped when nothing would be filled: a model call whose every output is discarded is pure
     * cost and latency on a page the reviewer is waiting on.
     */
    private DocumentContextModelGateway.Result inferIfNeeded(
            AssessmentContext supplied, String profile,
            List<com.app.docreview.retrievalcomparison.vector.VectorStorePort.SearchHit> hits) {

        boolean allSupplied = supplied.department() != null
                && supplied.standardFamily() != null
                && supplied.documentType() != null;
        if (allSupplied || hits.isEmpty()) {
            return DocumentContextModelGateway.Result.unavailable();
        }
        return documentContext.infer(profile, selection.vocabularyOf(hits));
    }

    /**
     * Which confidence tier to present, per the adaptive-context design.
     *
     * <p>The tier changes what the reviewer is TOLD, never the ranking maths — {@code AssessmentContext}
     * already bounds a label's influence to ±18%/−14% whatever the confidence. A high-confidence
     * inference is shown as a settled value; a low-confidence one is shown as a question worth
     * checking. Neither blocks, per the decision that there is no blocking dialog.
     */
    private static String tierOf(DocumentContextModelGateway.Result inferred) {
        if (!inferred.available() || inferred.isEmpty()) {
            return "NONE";
        }
        // Zero confidence is NONE, even though a value came back.
        //
        // Observed live: the model returned documentType="Procedure" and omitted `confidence`, which
        // clamps to 0.0 -- and this method then reported LOW, so the UI said "This might be" about a
        // value the model expressed no confidence in whatsoever. Presenting that as a weak suggestion
        // overstates it; there is nothing to weigh, so there is nothing to show.
        //
        // The value is still USED for ranking (bounded to ±18%/−14% as always). Only the invitation to
        // confirm it is withheld.
        if (inferred.confidence() <= 0.0) {
            return "NONE";
        }
        if (inferred.confidence() >= HIGH_CONFIDENCE) {
            return "HIGH";
        }
        return inferred.confidence() >= LOW_CONFIDENCE ? "MEDIUM" : "LOW";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** An absent query parameter arrives as "", which must not be compared against a payload value. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @param message  set only when candidates could not be computed, with the reason
     * @param inferred what the platform guessed about the document, for the reviewer to confirm or
     *                 correct. Never null; an unavailable inference is reported as a NONE tier rather
     *                 than an absent field, so the browser has one shape to handle.
     */
    public record CandidateResponse(
            String documentVersionId,
            List<StandardCandidate> candidates,
            String message,
            InferredContext inferred) {
    }

    /**
     * A proposed document context. Architecture §15.1.
     *
     * <p>Sent to the browser so the reviewer sees what was assumed and can change it. Values are
     * already confined to labels that exist on candidate standards, so anything here is selectable
     * rather than merely descriptive.
     *
     * @param tier NONE, LOW, MEDIUM, or HIGH. Governs presentation only.
     */
    public record InferredContext(
            String department,
            String standardFamily,
            String documentType,
            double confidence,
            String tier) {

        static InferredContext none() {
            return new InferredContext(null, null, null, 0.0, "NONE");
        }
    }
}
