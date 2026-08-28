package com.app.docreview.retrievalcomparison.comparison.aggregation;

import com.app.docreview.contracts.comparison.AtomicClauseResult;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rolls atomic-clause verdicts up to a parent control. Architecture §18.5.
 *
 * <p><strong>Deterministic, not model-decided.</strong> The model judges each clause; this computes
 * the parent. Asking the model for both invites inconsistency — a parent ALIGNED over a clause it
 * just marked CONTRADICTED — and makes the roll-up unauditable, because nobody can say why the
 * parent came out as it did.
 *
 * <p>The precedence that matters: <strong>any contradicted material clause makes the parent
 * CONTRADICTED</strong>, regardless of how many other clauses are satisfied. A process that requires
 * approval in four ways and explicitly permits self-approval in the fifth is not "mostly compliant" —
 * the exception is the finding. Averaging would bury it.
 */
@Component
public class ClauseAggregator {

    /**
     * @param coverage needed for the all-MISSING case: absence is only reportable when the document
     *                 was adequately searched (§17.6, §18.3)
     */
    public ComparisonStatus aggregate(List<AtomicClauseResult> clauseResults, SearchCoverage coverage) {
        if (clauseResults == null || clauseResults.isEmpty()) {
            // No clause was evaluated, so nothing was established either way. Reporting MISSING here
            // would assert a gap that was never tested.
            return ComparisonStatus.INSUFFICIENT_EVIDENCE;
        }

        boolean anyContradicted = false;
        boolean anyAligned = false;
        boolean anyPartial = false;
        boolean anyMissing = false;
        boolean anyInsufficient = false;

        for (AtomicClauseResult result : clauseResults) {
            switch (result.status()) {
                case CONTRADICTED -> anyContradicted = true;
                case ALIGNED -> anyAligned = true;
                case PARTIAL -> anyPartial = true;
                case MISSING -> anyMissing = true;
                case INSUFFICIENT_EVIDENCE -> anyInsufficient = true;
            }
        }

        // Contradiction wins outright. An explicit conflict is a finding no amount of compliance
        // elsewhere cancels — and it is the case a reviewer most needs to see first.
        if (anyContradicted) {
            return ComparisonStatus.CONTRADICTED;
        }

        // §18.5: "any unresolved material clause with insufficient evidence -> INSUFFICIENT_EVIDENCE
        // unless a stronger contradiction is present". Checked before MISSING and PARTIAL so a clause
        // we could not assess is never silently absorbed into a confident verdict.
        if (anyInsufficient) {
            return ComparisonStatus.INSUFFICIENT_EVIDENCE;
        }

        if (anyAligned && !anyPartial && !anyMissing) {
            return ComparisonStatus.ALIGNED;
        }

        // Some satisfied, some not: partially compliant, and the clause results say which.
        if (anyAligned) {
            return ComparisonStatus.PARTIAL;
        }

        if (anyMissing && !anyPartial) {
            // Every clause absent. Only reportable as MISSING when the search was adequate;
            // otherwise it is indistinguishable from a retrieval failure.
            return coverage == null || coverage.supportsAbsenceVerdict()
                    ? ComparisonStatus.MISSING
                    : ComparisonStatus.INSUFFICIENT_EVIDENCE;
        }

        // Nothing aligned, something partial: weaker than required throughout.
        return ComparisonStatus.PARTIAL;
    }
}
