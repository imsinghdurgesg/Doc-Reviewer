package com.app.docreview.reviewcore.comparison.web.dto;

import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.evidence.BoundingBox;
import com.app.docreview.contracts.evidence.CoordinateSpace;
import com.app.docreview.contracts.evidence.HighlightQuality;
import com.app.docreview.contracts.evidence.PageGeometry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Browser-facing comparison contracts. Architecture §7.4, §19.1.
 *
 * <p>These mirror {@code ui/review-web/src/models/comparison.ts} field for field. The UI's types were
 * written first and are the contract; a mismatch here is a runtime {@code undefined} in the viewer,
 * not a compile error, so the shapes are kept deliberately identical rather than "close enough".
 *
 * <p>Records, per §39 rule 8, and free of any entity type — the wire shape must be able to change
 * without dragging a MongoDB document with it.
 */
public final class ComparisonDtos {

    private ComparisonDtos() {
    }

    /** Request to start a review. */
    public record CreateComparisonRequest(
            @NotBlank String documentVersionId,

            /**
             * Explicit standard selection. Null or empty requests automatic selection (§15.1).
             *
             * <p>Not {@code @NotEmpty}: empty is a meaningful, distinct choice, and rejecting it
             * would remove the automatic path the architecture defines.
             */
            List<String> standardVersionIds,

            /**
             * Free-text assessment context from the reviewer, e.g. "covers hardware procurement for
             * the India office". Optional.
             *
             * <p>Steers WHICH parts of a standard are retrieved, so it changes the findings and is
             * retained with the comparison — §19.2 requires a review to be interpretable later, and a
             * finding produced under a stated focus cannot be read without knowing the focus.
             *
             * <p><strong>Length-capped.</strong> This text is appended to a retrieval profile and
             * reaches a prompt; an unbounded field is both a cost and an injection surface. 2000
             * characters is well past any genuine scoping note.
             */
            @Size(max = 2000, message = "Assessment context must be 2000 characters or fewer")
            String comparisonGuidance) {
    }

    /** An uploaded document, as the browser sees it. */
    public record UploadResponse(
            String documentVersionId,
            String filename,
            String mediaType,
            long sizeBytes,
            String state,
            String stateMessage,
            Integer pageCount,
            Integer spanCount,
            boolean readyForComparison,
            Instant createdAt) {
    }

    /**
     * Hydrated evidence. Architecture §16.5, §19.1.
     *
     * <p>Text and boxes are loaded from stored spans, never taken from model output — that is the
     * whole point of §16.5. The model returns span IDs; this is what those IDs resolve to.
     */
    public record HydratedEvidence(
            String spanId,
            Integer page,
            List<String> sectionPath,
            String text,
            List<BoundingBox> boundingBoxes,

            /**
             * The canonical node this span sits inside, and the span's character range within the
             * DOCUMENT's text.
             *
             * <p>These are what let the HTML viewer highlight without searching. Given the node, the
             * offsets become node-relative by subtracting the node's own {@code charStart}, and the
             * result is wrapped in a mark — the same direct resolution the PDF viewer gets from
             * {@code boundingBoxes}, expressed for a reflowed DOM instead of a fixed page.
             *
             * <p>Unlike geometry, these are NOT bound to a rendition: character offsets into parsed
             * text survive re-rendering, which is why an HTML pane keeps working for document types
             * that have no PDF rendition at all.
             *
             * <p>{@code textHash} is the HTML pane's equivalent of
             * {@code EvidenceSpan.geometryAppliesTo}: a viewer compares it against the node text it
             * actually rendered and suppresses the highlight on a mismatch. A re-parse that shifts
             * offsets is then detectable rather than silently marking the wrong sentence.
             */
            String parentNodeId,
            Integer charStart,
            Integer charEnd,
            String textHash,

            /**
             * How precisely {@code boundingBoxes} locate {@code text}.
             *
             * <p>Sent so the viewer can label an approximate highlight as approximate instead of
             * presenting every rectangle as exact. A non-empty box list is not by itself a claim of
             * precision — boxes may bound the containing paragraph rather than the cited sentence.
             */
            HighlightQuality highlightQuality,

            /** Convention {@code boundingBoxes} are expressed in, so the viewer can refuse unknown ones. */
            CoordinateSpace coordinateSpace,

            /**
             * The rendition the boxes were measured against.
             *
             * <p>The viewer compares this against the hash of the PDF it actually loaded and
             * suppresses geometry on a mismatch: a re-render can change pagination while leaving the
             * text identical, so rectangles from a different render would be drawn confidently in the
             * wrong place.
             */
            String renditionSha256,

            /** Page dimensions and rotation the boxes were measured against. */
            PageGeometry pageGeometry) {
    }

    /** Side-by-side evidence for one finding (§19.1). */
    public record FindingEvidenceResponse(
            String findingId,
            String requirementId,
            List<HydratedEvidence> standardEvidence,
            List<HydratedEvidence> processEvidence,

            /**
             * For an absence verdict, the closest passages that WERE considered (§18.6).
             *
             * <p>Returned separately from {@code processEvidence} so the viewer cannot present them
             * as proof of compliance — they are context showing where the search looked.
             */
            List<HydratedEvidence> nearestProcessEvidence) {
    }

    /** A standard evaluated within a comparison. */
    public record ComparisonReference(
            String standardId,
            String standardVersionId,
            String standardKey,
            String standardTitle,
            String standardVersion,
            /** Relevance, NOT compliance (§15.1). */
            double retrievalScore,
            String renderUrl,
            String textUrl,
            List<Finding> findings) {
    }

    public record DocumentSummary(
            String documentVersionId,
            String filename,
            Integer pageCount,
            String renderUrl) {
    }

    /** The review workspace's payload. Mirrors {@code ComparisonDetail} in the UI. */
    public record ComparisonDetailResponse(
            String comparisonId,
            String publicId,
            String status,
            DocumentSummary document,
            List<ComparisonReference> references,

            // §19.2: recorded so a historical comparison resolves exactly what produced it.
            String comparisonModelAlias,
            String promptVersion,
            String requirementSetVersion,
            String retrievalStrategyVersion,

            Integer evaluatedRequirements,
            Integer totalRequirements,
            String stateMessage,

            /**
             * Requirements that could not be evaluated, as {@code requirementId: reason} (§18.4).
             *
             * <p>Sent so the UI can name the affected controls instead of only their count. "1 of 15
             * could not be evaluated" with no detail leaves a reviewer unable to judge whether the
             * gap matters; naming the control makes the limitation bounded and checkable.
             */
            List<String> unevaluatedRequirements,

            /**
             * The reviewer's assessment focus, verbatim. Null when none was given.
             *
             * <p><strong>Why the reader needs it.</strong> Guidance steers which parts of a standard are
             * retrieved, so it changes which findings exist. A review conducted under "emergency change
             * controls only" and one conducted with no focus produce different findings from the same
             * document and the same standard — and without seeing the focus, a later reader cannot tell
             * which they are looking at. §19.2 requires a completed review to stay interpretable.
             *
             * <p>Read-only: a comparison's inputs are fixed once it has run, and an editable focus would
             * imply the findings would update to match.
             */
            String comparisonGuidance,

            /**
             * Requirement-by-requirement coverage. Architecture §17.6.
             *
             * <p><strong>Why the reader needs it.</strong> "Partially completed" tells a reviewer that
             * something was not measured without saying what, so they cannot tell whether the gap
             * matters. The ledger names each requirement and why it was not evaluated, which turns an
             * unactionable warning into a checkable list.
             *
             * <p>Null ONLY for a comparison that predates coverage persistence — see
             * {@code coverageSchemaVersion}. The UI must render that as "coverage not recorded", never
             * as full coverage. A comparison created under the coverage schema cannot reach a terminal
             * state without one, so null here on a recent comparison would itself be a defect.
             */
            CoverageSummary coverage,

            /**
             * True when this comparison predates coverage persistence and legitimately has none.
             *
             * <p>Sent explicitly rather than inferred from {@code coverage == null}, so the UI never
             * has to guess whether a missing summary is expected. The two readings demand different
             * words: "this review predates coverage tracking" versus "something is wrong".
             */
            boolean coverageExempt,

            Instant createdAt,
            Instant completedAt) {
    }

    /**
     * Coverage counts plus the entries a reviewer must act on.
     *
     * <p><strong>Counts are computed server-side, not left to the client.</strong> Two clients deriving
     * them independently is two chances to disagree about whether a review was complete — and the
     * coverage claim is exactly the one that must not vary by who is reading it.
     *
     * @param uncovered entries needing attention: not evaluated, failed, or applicability unresolved.
     *                  Plainly evaluated entries are omitted — a 400-requirement standard would
     *                  otherwise send 400 rows to render a four-line summary.
     */
    public record CoverageSummary(
            int totalRequirements,

            /**
             * Requirements a comparison actually ran against — the PARENT of the two lines below.
             *
             * <p>{@code compared == applicabilityConfirmed + applicabilityReviewRequired}, always.
             * Presented as a parent with children rather than as four sibling numbers, because
             * "evaluated 3, applicability review required 1" out of 5 invites the reader to add them
             * and conclude 4 of 5 were handled. The subset relationship has to be visible in the
             * shape, not inferred from a footnote.
             */
            long compared,
            /** Compared, and the requirement is established as governing this document. */
            long applicabilityConfirmed,
            /** Compared, but whether it governs this document is unresolved. A subset of compared. */
            long applicabilityReviewRequired,

            /** Excluded by a governed applicability decision. Disjoint from compared. */
            long notApplicable,
            /** Produced no verdict at all — never attempted, or failed. Disjoint from compared. */
            long notEvaluated,

            boolean complete,
            List<CoverageEntry> uncovered) {

        /**
         * The partition that must hold: every requirement is compared, not applicable, or not
         * evaluated — exactly one of the three.
         *
         * <p>Exposed so a client can assert it rather than trust it. A summary whose parts do not
         * account for the whole is a coverage claim with a hole in it, and the hole is invisible in
         * any single number.
         */
        public boolean countsReconcile() {
            return compared + notApplicable + notEvaluated == totalRequirements
                    && applicabilityConfirmed + applicabilityReviewRequired == compared;
        }
    }

    /**
     * One requirement that was not plainly evaluated.
     *
     * @param requirementTitle what the requirement demands. Without it a reviewer sees an id and has
     *                         no way to judge whether the gap is material.
     * @param reason           why it was not evaluated. Never null for a FAILED entry — an unexplained
     *                         gap is indistinguishable from a bug.
     */
    public record CoverageEntry(
            String requirementId,
            String requirementTitle,
            String standardVersionId,
            String state,
            String reason) {
    }

    /** History row. Deliberately small — a list must not carry every finding. */
    public record ComparisonSummary(
            String publicId,
            String documentVersionId,
            String filename,
            String status,
            int findingCount,
            int actionableFindingCount,
            /**
             * The standard this review was primarily measured against, for the history list.
             *
             * <p>The FIRST reference, which is the highest-ranked one (§15.1). A comparison can span
             * several standards, but a list needs one identifying line, and the top-ranked reference
             * is what a reviewer recognises the review by. Null for a comparison that failed before
             * any reference resolved.
             */
            String topStandardTitle,
            String topStandardKey,
            /**
             * Who ran the review.
             *
             * <p>Captured since the first release but never returned, so history could not say whose
             * review a row was. That matters here more than in most tools: every reviewer sees every
             * comparison, so without attribution a reviewer cannot tell their own work from a
             * colleague's — and a finding someone acts on should name who produced it.
             *
             * <p>The email as authenticated, not a display name: it is the identity the authorization
             * decision was made against, and a display name is neither unique nor stable.
             */
            String reviewer,
            Instant createdAt,
            Instant completedAt) {
    }
}
