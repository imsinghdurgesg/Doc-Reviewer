package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the coverage ledger for a comparison. Architecture §17.6, §18.4.
 *
 * <p><strong>Inventory first, findings second.</strong> The ledger is seeded from the material
 * requirements of the selected standards, then each is marked off against the findings actually
 * produced. Doing it the other way round — deriving coverage from the findings — cannot detect the
 * failure the ledger exists for: a requirement that produced no finding because it was never evaluated
 * leaves no trace in a list of findings.
 *
 * <p>Only MATERIAL requirements are expected. An advisory requirement is never compared (§12.2), so
 * listing it as untested would report a permanent gap that no comparison could ever close.
 */
@Service
@Slf4j
public class CoverageLedgerService {

    /**
     * @param requirements  every requirement loaded for this comparison, material or not
     * @param findings      what evaluation actually produced
     * @param failedIds     requirement ids whose evaluation raised an error, from the job runner
     * @param skipped       requirement id to reason, for deliberate exclusions
     * @param notAttemptedReasons requirement id to reason, for requirements that were expected but
     *                      never attempted — the per-comparison cap being the case that matters. They
     *                      become PENDING, which blocks completion, and the reason is what turns
     *                      "not evaluated" into something a reviewer can act on.
     */
    /**
     * Builds a ledger where nothing was dropped before evaluation.
     *
     * <p>The common case: every expected requirement was attempted, so an unattempted one is a defect
     * rather than a budget decision and needs no explanatory reason.
     */
    public CoverageLedger build(String comparisonId,
                                List<StandardRequirementDocument> requirements,
                                List<Finding> findings,
                                Set<String> failedIds,
                                Map<String, String> skipped) {
        return build(comparisonId, requirements, findings, failedIds, skipped, Map.of());
    }

    public CoverageLedger build(String comparisonId,
                                List<StandardRequirementDocument> requirements,
                                List<Finding> findings,
                                Set<String> failedIds,
                                Map<String, String> skipped,
                                Map<String, String> notAttemptedReasons) {

        // Kept as the finding, not just its id: applicability travels on the finding, and the ledger
        // needs it to tell "evaluated and ready to act on" from "evaluated with an open scope
        // question". Deriving that from a second source could disagree with the finding a reviewer
        // reads (§12.5).
        var byRequirement = new LinkedHashMap<String, Finding>();
        if (findings != null) {
            for (var finding : findings) {
                byRequirement.putIfAbsent(finding.requirementId(), finding);
            }
        }
        var failed = failedIds == null ? Set.<String>of() : failedIds;
        var skips = skipped == null ? Map.<String, String>of() : skipped;
        var notAttempted = notAttemptedReasons == null
                ? Map.<String, String>of() : notAttemptedReasons;

        List<CoverageLedger.Entry> entries = new ArrayList<>();
        for (var requirement : requirements == null ? List.<StandardRequirementDocument>of()
                : requirements) {
            if (!requirement.isMaterial()) {
                // Advisory requirements are out of scope for comparison (§12.2). Including them would
                // permanently depress coverage against requirements that are never evaluated by design.
                continue;
            }

            String id = requirement.getRequirementId();
            CoverageLedger.CoverageState state;
            String detail = null;

            // Order matters. A requirement that both produced a finding and reported an error is
            // EVALUATED: the verdict exists, so its absence cannot be misread. Checking failure first
            // would discard a real finding.
            var finding = byRequirement.get(id);
            if (finding != null) {
                // Compared either way. The split is about what the reviewer must do next: an
                // UNCERTAIN verdict is actionable only once someone confirms the requirement governs
                // this document, so it is counted separately rather than folded into EVALUATED.
                if (finding.applicability() == ApplicabilityStatus.UNCERTAIN) {
                    state = CoverageLedger.CoverageState.UNCERTAIN_APPLICABILITY;
                    detail = finding.applicabilityReason() == null
                            ? "Applicability could not be determined"
                            : finding.applicabilityReason();
                } else {
                    state = CoverageLedger.CoverageState.EVALUATED;
                }
            } else if (failed.contains(id)) {
                state = CoverageLedger.CoverageState.FAILED;
                detail = "Evaluation failed";
            } else if (skips.containsKey(id)) {
                state = CoverageLedger.CoverageState.SKIPPED;
                detail = skips.get(id);
            } else {
                // Expected, never attempted, no error recorded. The state that must block completion.
                state = CoverageLedger.CoverageState.PENDING;
                // A reason when the caller supplied one. A requirement removed by the per-comparison
                // cap is not a bug, but "not evaluated" with no explanation is unactionable — the
                // reviewer cannot tell a budget limit from a dropped control.
                detail = notAttempted.get(id);
            }

            entries.add(new CoverageLedger.Entry(
                    requirement.getStandardVersionId(),
                    requirement.getRequirementSetVersion(),
                    id, state, detail,
                    // The title, so an uncovered requirement is nameable in the UI. A reviewer cannot
                    // act on "CHG-17 was not evaluated" without knowing what CHG-17 requires, and the
                    // finding that would have carried the title is precisely what is missing.
                    requirement.getTitle()));
        }

        var ledger = new CoverageLedger(comparisonId, entries);
        // Counts only, never requirement text (§35).
        log.info("Coverage for {}: {}/{} evaluated, {} untested, complete={}",
                comparisonId, ledger.evaluatedCount(), ledger.expectedCount(),
                ledger.untestedCount(), ledger.isComplete());
        return ledger;
    }
}
