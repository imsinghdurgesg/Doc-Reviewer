package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;
import com.app.docreview.contracts.enums.Severity;

import java.util.List;

/**
 * A validated, evidence-linked finding. Architecture §18.6.
 *
 * <p>This is the business record — what a reviewer acts on and what an auditor inspects. It exists
 * only after §18.1–18.3 validation, so a {@code Finding} is by construction something whose evidence
 * has been checked to exist, belong to the right document versions, and match its status.
 *
 * <p>{@code nearestProcessSpanIds} is the field that makes a MISSING verdict reviewable. A gap with
 * no evidence at all is unfalsifiable — the reviewer cannot tell whether the tool looked in the right
 * place. Naming the closest passage that was considered converts "absent" into "we looked here, and
 * this is the nearest thing we found".
 *
 * <p>Aligned controls are retained too (§18.6), not only gaps: a findings list showing three
 * problems and nothing else gives no sense of how much was actually reviewed.
 */
public record Finding(
        String findingId,
        String comparisonId,
        String standardVersionId,
        String requirementSetVersion,
        String requirementId,
        String parentControlId,
        String requirementTitle,
        ComparisonStatus status,
        GapType gapType,
        /** From standard metadata or administrator rules, never the model (§17.7). */
        Severity severity,
        List<String> standardSpanIds,
        List<String> processSpanIds,
        List<String> nearestProcessSpanIds,
        List<AtomicClauseResult> clauseResults,
        String reason,
        String recommendedAction,
        double confidence,
        boolean needsHumanReview,

        /**
         * Whether the requirement APPLIED, separate from whether it was satisfied. §12.5, §17.6.
         *
         * <p>Travels alongside {@code status} rather than replacing it because they answer different
         * questions. Forcing "does not apply" onto the compliance scale has no correct answer: MISSING
         * reports a gap the standard never demanded, ALIGNED claims unassessed compliance, and
         * INSUFFICIENT_EVIDENCE blames the document's evidence when none was needed.
         *
         * <p>Null on findings recorded before applicability existed. {@link #applicability()} normalises
         * that to APPLICABLE — a historical finding was compared, so it applied.
         */
        ApplicabilityStatus applicabilityStatus,

        /** Why the requirement was excluded or is unclear. Null when it simply applies. */
        String applicabilityReason,

        /**
         * What decided applicability: the resolver version, plus any governed override batches applied.
         * §19.2.
         *
         * <p><strong>Why it is snapshotted on the finding.</strong> Condition roles are governed data
         * that changes as policy owners resolve open questions. A finding recording only its status
         * would silently re-explain itself under a later review — so reopening a six-month-old
         * comparison could show an exclusion attributed to a rationale that did not exist when it ran.
         * Storing the deciding version makes the decision reproducible rather than merely plausible.
         *
         * <p>Null on findings recorded before overrides existed.
         */
        String applicabilityDecidedBy,

        /**
         * When this finding was produced by a TARGETED RETRY, and under what versions. §19.2.
         *
         * <p><strong>Why a per-finding stamp exists at all.</strong> A comparison records one set of
         * prompt, model and override versions, taken at completion. Retrying a single failed
         * requirement later may run under different ones — a newer model alias, a confirmed governance
         * override — so an amended comparison can contain findings from two provenance sets while its
         * header claims one.
         *
         * <p>Null on every finding from the original run, which is the overwhelming majority. A
         * non-null value is the honest signal that this particular row was produced separately, and
         * carries the versions that produced it so a reader can tell how it differs.
         */
        RetryProvenance retriedAs
) {

    /**
     * The versions a retried finding actually ran under.
     *
     * @param at             when the retry ran
     * @param promptVersion  the comparison prompt in force at retry time
     * @param modelAlias     the model that produced it
     * @param overrideBatch  the governance override set applied, if any
     */
    public record RetryProvenance(java.time.Instant at, String promptVersion, String modelAlias,
                                  String overrideBatch) {
    }

    /**
     * §18.6: only these four statuses produce a finding a reviewer must act on.
     *
     * <p>A requirement that does not apply is never actionable, whatever its comparison status — it was
     * not measured, so there is nothing to act on.
     */
    public boolean actionable() {
        return applicability().shouldCompare() && status != ComparisonStatus.ALIGNED;
    }

    /**
     * The applicability status, defaulting to APPLICABLE.
     *
     * <p>Never returns null, so no caller has to decide what a missing value means. APPLICABLE is the
     * right default twice over: it is the ordinary case, and a finding that exists at all was produced
     * by comparing the requirement — which only happens when it applied.
     */
    public ApplicabilityStatus applicability() {
        return applicabilityStatus == null ? ApplicabilityStatus.APPLICABLE : applicabilityStatus;
    }
}
