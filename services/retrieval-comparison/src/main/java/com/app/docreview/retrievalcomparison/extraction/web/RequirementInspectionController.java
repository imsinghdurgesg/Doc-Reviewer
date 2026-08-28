package com.app.docreview.retrievalcomparison.extraction.web;

import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.ConditionRoleSource;
import com.app.docreview.retrievalcomparison.comparison.application.EffectiveConditionResolver;
import com.app.docreview.retrievalcomparison.comparison.application.PolicyOwnerQueueService;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Requirement inspection. Architecture §12 (requirement administration/inspection API).
 *
 * <p><strong>Internal only.</strong> §3 states the browser talks solely to review-core; this is
 * consumed by review-core, not by React. It is read-only by design — publishing a requirement set is
 * the extraction pipeline's job, and an endpoint that let an administrator edit one directly would
 * break the provenance record that makes a requirement set reproducible (§12.9).
 */
@RestController
@RequestMapping("/internal/requirements")
@RequiredArgsConstructor
public class RequirementInspectionController {

    private final StandardRequirementRepository requirementRepository;
    private final RequirementSetVersionRepository setVersionRepository;
    private final EffectiveConditionResolver effectiveConditions;
    private final PolicyOwnerQueueService policyOwnerQueue;

    /**
     * One condition as an administrator needs to see it: what the model said, what a reviewer decided,
     * and which of the two governs a comparison. §12.5, §19.2.
     *
     * <p><strong>Both roles, never just the effective one.</strong> Showing only the resolved answer
     * would hide the disagreement — and the disagreement is the measurement. A review of 115 predicted
     * scope gates reclassified 113; that is only visible if the original prediction survives alongside
     * the correction.
     *
     * @param canDriveNotApplicable whether this condition may remove a requirement from a review.
     *                              Separate from {@code effectiveRole} on purpose: a confirmed scope
     *                              gate that nobody authorised still cannot exclude, and an
     *                              administrator needs to see that distinction rather than infer it.
     * @param reclassified          true when review changed the role — the rows worth attention.
     *                              A real component rather than a derived accessor because Jackson
     *                              serialises only components, and a client filtering on a field the
     *                              response never carried silently shows nothing.
     * @param requirementText       the clause the condition was extracted from.
     *                              <p>Carried because a condition alone is often unjudgeable. The
     *                              extractor truncated "When the change is a material security or
     *                              privacy change" to "When it is" — meaningless in isolation, obvious
     *                              beside its requirement. Asking someone to rule on the fragment
     *                              without the clause invites a guess.
     * @param conditionSourceSpanIds the span the condition came from, so the claim is checkable against
     *                              the standard rather than taken on trust (§16.5).
     */
    public record ConditionInspection(
            String requirementId,
            String conditionText,
            ConditionRole extractedRole,
            ConditionRole effectiveRole,
            ConditionRoleSource source,
            boolean canDriveNotApplicable,
            String reviewerNotes,
            String overrideBatchId,
            boolean reclassified,
            String requirementText,
            List<String> conditionSourceSpanIds) {

        public ConditionInspection(
                String requirementId, String conditionText,
                ConditionRole extractedRole, ConditionRole effectiveRole,
                ConditionRoleSource source, boolean canDriveNotApplicable,
                String reviewerNotes, String overrideBatchId,
                String requirementText, List<String> conditionSourceSpanIds) {
            this(requirementId, conditionText, extractedRole, effectiveRole, source,
                    canDriveNotApplicable, reviewerNotes, overrideBatchId,
                    extractedRole != effectiveRole,
                    requirementText,
                    conditionSourceSpanIds == null ? List.of() : List.copyOf(conditionSourceSpanIds));
        }
    }

    /** Extraction runs for a standard version, newest first. */
    @GetMapping("/sets/{standardVersionId}")
    public List<RequirementSetVersionDocument> sets(@PathVariable String standardVersionId) {
        return setVersionRepository.findByStandardVersionIdOrderByCreatedAtDesc(standardVersionId);
    }

    /**
     * Requirements in one set.
     *
     * @param materialOnly when true, returns only what a comparison will actually evaluate. Default
     *                     false so an administrator reviewing extraction quality sees everything,
     *                     including the advisory requirements that were deliberately excluded —
     *                     hiding them would make the extractor look like it had missed them.
     */
    @GetMapping("/sets/{standardVersionId}/{requirementSetVersion}")
    public List<StandardRequirementDocument> requirements(
            @PathVariable String standardVersionId,
            @PathVariable String requirementSetVersion,
            @RequestParam(defaultValue = "false") boolean materialOnly) {

        return materialOnly
                ? requirementRepository
                    .findByStandardVersionIdAndRequirementSetVersionAndMaterial(
                            standardVersionId, requirementSetVersion, true)
                : requirementRepository.findByStandardVersionIdAndRequirementSetVersion(
                        standardVersionId, requirementSetVersion);
    }

    /**
     * Applicability questions awaiting a policy owner, across every standard. §12.5, §19.2.
     *
     * <p>Grouped by condition phrase rather than listed per row, because the same phrase recurs across
     * versions and is one decision. Ordered by how often it occurs.
     *
     * <p>Nothing here blocks a comparison: every queued condition resolves to UNKNOWN and cannot
     * exclude, so its requirement is compared and flagged. The queue makes uncertainty actionable
     * instead of leaving it to be rediscovered.
     */
    @GetMapping("/policy-owner-queue")
    public List<PolicyOwnerQueueService.OpenQuestion> policyOwnerQueue() {
        return policyOwnerQueue.openQuestions();
    }

    /**
     * The requirement set a comparison would actually evaluate against, for one standard version.
     *
     * <p><strong>Why this exists.</strong> review-core keeps a denormalised
     * {@code requirementSetVersion} on its own version record, and it goes stale: a re-extraction run
     * against this service updates the set here without writing back, so review-core reported
     * {@code REQSET-V1} for every version long after V3 was live. A caller that trusted it asked for a
     * set with no conditions and got an empty answer that looked like "nothing to review".
     *
     * <p>The worker owns requirement sets (§22.3), so it is the only honest source for which one is
     * current. Callers should resolve the set here rather than carrying their own copy.
     *
     * <p>Returns 404 when no VALIDATED set exists — a version whose extraction never ran, or produced
     * only NEEDS_REVIEW. Deliberately not the newest set of any status: a SHADOW set is for evaluation
     * and must never be presented as what a comparison uses.
     */
    @GetMapping("/sets/{standardVersionId}/current")
    public ResponseEntity<RequirementSetVersionDocument> currentSet(
            @PathVariable String standardVersionId) {
        return setVersionRepository
                .findFirstByStandardVersionIdAndValidationStatusOrderByCreatedAtDesc(
                        standardVersionId, StandardRequirementDocument.ValidationStatus.VALIDATED)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Every applicability condition in one set, with its extracted role, governed role and provenance.
     *
     * <p>This is the surface that makes the override model auditable: an administrator can see which
     * conditions a person reviewed, which the model still owns unreviewed, which are authorised to
     * exclude a control, and which are open questions waiting on a policy owner.
     *
     * @param reclassifiedOnly when true, returns only conditions where review CHANGED the role.
     *                         Default false: an administrator assessing extraction quality needs the
     *                         agreements too, since precision is meaningless without them.
     */
    @GetMapping("/sets/{standardVersionId}/{requirementSetVersion}/conditions")
    public List<ConditionInspection> conditions(
            @PathVariable String standardVersionId,
            @PathVariable String requirementSetVersion,
            @RequestParam(defaultValue = "false") boolean reclassifiedOnly) {

        var requirements = requirementRepository
                .findByStandardVersionIdAndRequirementSetVersion(standardVersionId, requirementSetVersion);

        var rows = new java.util.ArrayList<ConditionInspection>();
        for (var requirement : requirements) {
            var applicability = requirement.toExtractedRequirement().applicability();
            if (applicability == null) {
                continue;
            }
            // Resolved through the SAME overlay a comparison uses. Recomputing the precedence here
            // would let this page and the actual decision drift apart, and the page would be the one
            // people trusted.
            var effective = effectiveConditions.resolve(
                    standardVersionId, requirementSetVersion, requirement.getRequirementId(),
                    applicability.effectiveConditions());

            // Iterating the resolved list and reading the extracted role off the condition it CARRIES,
            // rather than index-matching the two lists. Positional coupling across a call boundary
            // would silently mis-pair every row if the resolver ever filtered or reordered.
            for (var e : effective) {
                var row = new ConditionInspection(
                        requirement.getRequirementId(),
                        e.condition().text(),
                        e.condition().role(),
                        e.effectiveRole(),
                        e.source(),
                        e.canDriveNotApplicable(),
                        e.reviewerNotes(),
                        e.overrideBatchId(),
                        requirement.getNormalizedRequirement(),
                        e.condition().conditionSourceSpanIds());
                if (!reclassifiedOnly || row.reclassified()) {
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }
}
