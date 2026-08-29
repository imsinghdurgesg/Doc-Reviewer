package com.app.docreview.contracts.comparison;

import java.util.List;
import java.util.Map;

/**
 * Which requirements were actually evaluated, and which were not. Architecture §17.6, §18.4, §19.2.
 *
 * <p><strong>The problem this solves.</strong> A comparison reports findings, and a reader naturally
 * treats the absence of a finding as compliance. That inference is only valid if every material
 * requirement was actually tested. When 8 of 30 requirements were evaluated — because the job partially
 * failed, or a standard was never selected — the report looks identical to a clean review of all 30.
 * §17.6 makes the distinction explicit: a requirement that was never tested is not evidence of
 * anything.
 *
 * <p><strong>Built from the requirement inventory, not from the findings.</strong> Deriving coverage by
 * counting findings cannot detect the failure it exists to catch: a requirement that produced no finding
 * because it was never evaluated is invisible in a list of findings. So the ledger starts from the
 * expected material requirements of every selected standard and marks each one off.
 *
 * <p><strong>Completion invariant.</strong> {@link #isComplete()} is false while any requirement is
 * {@link CoverageState#PENDING}. A comparison must not be reported COMPLETED with pending entries — that
 * is the enforcement point for §18.4's partial-completion rule, and it holds regardless of what the job
 * runner believes about its own success.
 */
public record CoverageLedger(

        String comparisonId,

        /** One entry per expected material requirement, across every selected standard. */
        List<Entry> entries) {

    public CoverageLedger {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** How a single requirement fared. */
    public enum CoverageState {

        /** Expected, not yet attempted. A ledger with any of these is not complete. */
        PENDING,

        /** Attempted and produced a finding, whatever that finding says. */
        EVALUATED,

        /**
         * Attempted and failed — a model error, a timeout, a validation rejection.
         *
         * <p>Distinct from PENDING: this one will not resolve by waiting. Distinct from EVALUATED:
         * there is no verdict, so its absence from the findings must not read as compliance.
         */
        FAILED,

        /**
         * Deliberately not evaluated, with a recorded reason.
         *
         * <p>For requirements ruled inapplicable (Phase 2) or excluded by an administrator. Legitimate,
         * but it must be VISIBLE — a silently skipped requirement is indistinguishable from a passing
         * one.
         */
        SKIPPED,

        /**
         * Evaluated, but nobody has established whether the requirement governs this document. §12.5.
         *
         * <p>Separate from EVALUATED because the reviewer's next action differs. An EVALUATED verdict is
         * ready to act on; this one carries a verdict AND an unanswered scope question, so acting on it
         * without confirming applicability risks working from a finding the standard never demanded.
         *
         * <p>Separate from SKIPPED because it WAS compared — the requirement is covered, the finding is
         * real, and counting it as skipped would understate coverage and imply the control was dropped.
         *
         * <p>Terminal, so it does not block completion: doubt about scope must not stall a review
         * (§17.6). It surfaces as its own count instead.
         */
        UNCERTAIN_APPLICABILITY,

        /**
         * Compared, but our extracted representation of the standard was incomplete. §18.3.
         *
         * <p>The requirement WAS evaluated and produced evidence, so this is not a failure and not an
         * untested requirement — treating it as either would misdirect the reviewer. What is missing is
         * a clause for an obligation the standard states, which is a defect in OUR extraction rather
         * than in the uploaded document.
         *
         * <p>Terminal, and deliberately not retryable: no number of model calls can supply a clause the
         * extractor never produced. Before this state existed the case surfaced as FAILED and invited a
         * retry that could not succeed — CHG-08-R2 consumed eight model calls that way.
         *
         * <p>Excluded from the alignment conclusion, because the obligation was never assessed.
         */
        EXTRACTION_REVIEW_REQUIRED;

        /**
         * Whether a comparison actually ran and produced a verdict for this requirement.
         *
         * <p>The distinction coverage arithmetic depends on. A positive test on the verdict-bearing
         * states, so a future state defaults to "no verdict" — the safe direction, because counting a
         * requirement as evaluated when it was not is how a report claims coverage it does not have.
         *
         * <p>{@code EXTRACTION_REVIEW_REQUIRED} counts: the comparison ran, cited evidence, and left a
         * reviewable result. Its exclusion from the CONCLUSION is a separate question from whether it
         * was covered — see {@link #conclusionEligible()}.
         */
        public boolean producedVerdict() {
            return this == EVALUATED || this == UNCERTAIN_APPLICABILITY
                    || this == EXTRACTION_REVIEW_REQUIRED;
        }

        /**
         * Whether this entry may contribute to the alignment conclusion.
         *
         * <p>Separate from {@link #producedVerdict()} because coverage and conclusion are different
         * claims. An extraction-gap result is covered — it was compared and is reviewable — but must not
         * support a positive compliance statement, since a stated obligation went unassessed.
         */
        public boolean conclusionEligible() {
            return this == EVALUATED || this == UNCERTAIN_APPLICABILITY;
        }

        /**
         * Whether re-running the model could change this outcome.
         *
         * <p>False for an extraction gap: the missing clause is upstream of the model, so a retry spends
         * calls to reach the same wall. This is what makes the state terminal-and-reviewable rather than
         * a coverage gap the UI would offer to retry.
         */
        public boolean retryable() {
            return this == FAILED || this == PENDING;
        }

        /**
         * Whether this state will change by waiting. Only PENDING will.
         *
         * <p>Named rather than inlined at the single call site: "terminal" is the concept
         * {@code isComplete()} enforces, and a reader should not have to infer it from a negation.
         */
        public boolean terminal() {
            return this != PENDING;
        }
    }

    /**
     * @param requirementId  the requirement's stable id within its set
     * @param state          how it fared
     * @param detail         why, for FAILED, SKIPPED and UNCERTAIN_APPLICABILITY. Null otherwise.
     *                       Never document text (§35) — for an uncertain entry this is the standard's
     *                       own stated condition, which is standard content and legitimate to show a
     *                       reviewer, but must not be logged.
     * @param requirementTitle the requirement's title, so an uncovered entry is nameable.
     *                       <p>Carried on the ledger because the finding that would normally supply it
     *                       is exactly what is missing: "CHG-17 was not evaluated" is unactionable
     *                       without knowing what CHG-17 requires.
     */
    public record Entry(
            String standardVersionId,
            String requirementSetVersion,
            String requirementId,
            CoverageState state,
            String detail,
            String requirementTitle) {
    }

    /**
     * True only when every entry reached a terminal state.
     *
     * <p>FAILED, SKIPPED and UNCERTAIN_APPLICABILITY all count as terminal: none will change by
     * waiting, and each is reported with its own count. Only PENDING blocks completion.
     */
    public boolean isComplete() {
        return entries.stream().allMatch(e -> e.state().terminal());
    }

    /**
     * Requirements whose absence from the findings must NOT be read as compliance (§17.6).
     *
     * <p>This is the number a report has to surface. "27 of 30 requirements evaluated" is honest;
     * "3 findings" alone is not, when three requirements were never tested.
     *
     * <p>{@code UNCERTAIN_APPLICABILITY} is EXCLUDED: those requirements were compared and produced a
     * verdict, so counting them as untested would understate coverage and imply a control was dropped
     * when it was in fact measured. Their open scope question is reported by its own count instead.
     */
    public long untestedCount() {
        return entries.stream()
                .filter(e -> !e.state().producedVerdict())
                .count();
    }

    /**
     * Requirements that produced a usable verdict.
     *
     * <p>Includes {@code UNCERTAIN_APPLICABILITY}: the comparison ran and the finding is real. What is
     * unresolved is whether the requirement applies, which is reported separately rather than by
     * pretending no evaluation happened.
     */
    public long evaluatedCount() {
        return entries.stream().filter(e -> e.state().producedVerdict()).count();
    }

    /** Requirements evaluated but carrying an unresolved scope question (§12.5). */
    public long uncertainApplicabilityCount() {
        return entries.stream()
                .filter(e -> e.state() == CoverageState.UNCERTAIN_APPLICABILITY).count();
    }

    /**
     * Requirements compared against an incomplete extraction of the standard (§18.3).
     *
     * <p>Reported as its own line rather than folded into either the evaluated total or the untested
     * total: it is neither a clean verdict nor a gap in coverage, and both roundings hide the fact that
     * the system, not the document, is what needs attention.
     */
    public long extractionReviewRequiredCount() {
        return entries.stream()
                .filter(e -> e.state() == CoverageState.EXTRACTION_REVIEW_REQUIRED).count();
    }

    /**
     * Requirements whose verdict may support the alignment conclusion (§18.6).
     *
     * <p>Always less than or equal to {@link #evaluatedCount()}. The difference is the requirements that
     * were measured but cannot be concluded from.
     */
    public long conclusionEligibleCount() {
        return entries.stream().filter(e -> e.state().conclusionEligible()).count();
    }

    /** Requirements deliberately excluded, with a reason. */
    public long notApplicableCount() {
        return entries.stream().filter(e -> e.state() == CoverageState.SKIPPED).count();
    }

    public int expectedCount() {
        return entries.size();
    }

    /** Counts by state, for a report line. Absent states are omitted rather than reported as zero. */
    public Map<CoverageState, Long> byState() {
        return entries.stream().collect(java.util.stream.Collectors.groupingBy(
                Entry::state, java.util.stream.Collectors.counting()));
    }

    /**
     * The fraction of expected requirements that were actually evaluated, 0.0–1.0.
     *
     * <p>An EMPTY ledger returns 0.0, not 1.0. "Nothing was expected" and "everything was covered" are
     * different claims, and treating the first as full coverage would report a comparison that
     * selected no standards as complete.
     */
    public double coverageRatio() {
        return entries.isEmpty() ? 0.0 : (double) evaluatedCount() / entries.size();
    }
}
