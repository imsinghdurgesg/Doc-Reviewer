package com.app.docreview.retrievalcomparison.extraction.web;

import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.ConditionRoleSource;
import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.retrievalcomparison.comparison.application.EffectiveConditionResolver;
import com.app.docreview.retrievalcomparison.comparison.application.PolicyOwnerQueueService;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Requirement inspection. Architecture §12 (requirement administration/inspection API).
 *
 * <p><strong>Internal only.</strong> §3 states the browser talks solely to review-core; this is
 * consumed by review-core, not by React.
 *
 * <p><strong>The requirement set itself stays read-only.</strong> Publishing one is the extraction
 * pipeline's job, and an endpoint that let an administrator edit a requirement directly would break the
 * provenance record that makes a set reproducible (§12.9).
 *
 * <p>Condition REVIEW is the one exception, and it does not violate that rule: a review decision is a
 * governed OVERLAY stored beside the extraction, never a rewrite of it. The extracted role survives
 * alongside the reviewed one, which is what makes the disagreement measurable — see
 * {@link #reviewCondition}.
 */
@RestController
@Slf4j
@RequestMapping("/internal/requirements")
@RequiredArgsConstructor
public class RequirementInspectionController {

    private final StandardRequirementRepository requirementRepository;
    private final RequirementSetVersionRepository setVersionRepository;
    private final EffectiveConditionResolver effectiveConditions;
    private final PolicyOwnerQueueService policyOwnerQueue;
    /** Governed review decisions: read by the resolver, written by the review endpoint below. */
    private final RequirementConditionOverrideRepository overrideRepository;

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

    /**
     * Records or revises one condition's review decision. Architecture §12.5.
     *
     * <p><strong>Why a single condition rather than a batch.</strong> The CSV importer refuses a whole
     * file on any malformed row, because a half-applied batch leaves some requirements governed and
     * others not with nothing on screen to say which. A UI edit has the opposite property: it is one
     * deliberate decision a person is looking at, and its outcome is visible immediately. Batching
     * those would reintroduce the partial-failure problem for no benefit.
     *
     * <p>The condition is identified by its TEXT within a requirement, not by an index. Positions shift
     * when a requirement is re-extracted, and an index would then silently attach a review decision —
     * possibly an exclusion — to a different condition.
     *
     * @param authoriseExclusion honoured only for a CONFIRMED SCOPE_GATE; see
     *                           {@link RequirementConditionOverride#revise}
     */
    @PostMapping("/sets/{standardVersionId}/{requirementSetVersion}/conditions/review")
    public ResponseEntity<ConditionInspection> reviewCondition(
            @PathVariable String standardVersionId,
            @PathVariable String requirementSetVersion,
            @RequestBody ConditionReviewRequest request) {

        if (request == null || isBlank(request.requirementId()) || isBlank(request.conditionText())) {
            return ResponseEntity.badRequest().build();
        }
        // A note is REQUIRED, not optional. The stored decision is what a reader sees months later, and
        // a role change with no rationale is unreviewable — it says what was decided and nothing about
        // why, which is the part that cannot be reconstructed.
        if (isBlank(request.reviewerNotes())) {
            return ResponseEntity.badRequest().build();
        }
        if (request.reviewedRole() == null || request.reviewStatus() == null) {
            return ResponseEntity.badRequest().build();
        }

        var requirement = requirementRepository
                .findByStandardVersionIdAndRequirementSetVersion(standardVersionId, requirementSetVersion)
                .stream()
                .filter(r -> request.requirementId().equals(r.getRequirementId()))
                .findFirst()
                .orElse(null);
        if (requirement == null) {
            return ResponseEntity.notFound().build();
        }

        // The condition must EXIST on the requirement. Without this a typo would create an override
        // that matches nothing, and the row would sit in the governance list looking authoritative
        // while affecting no comparison at all.
        var applicability = requirement.toExtractedRequirement().applicability();
        boolean known = applicability != null && applicability.effectiveConditions().stream()
                .anyMatch(c -> normalise(c.text()).equals(normalise(request.conditionText())));
        if (!known) {
            return ResponseEntity.notFound().build();
        }

        var existing = overrideRepository
                .findByStandardVersionIdAndRequirementSetVersionAndRequirementId(
                        standardVersionId, requirementSetVersion, request.requirementId())
                .stream()
                .filter(o -> normalise(o.getConditionText()).equals(normalise(request.conditionText())))
                .findFirst()
                .orElse(null);

        var batch = "UI-" + safe(request.reviewedBy()) + "-" + java.time.LocalDate.now();
        if (existing == null) {
            var extracted = applicability.effectiveConditions().stream()
                    .filter(c -> normalise(c.text()).equals(normalise(request.conditionText())))
                    .findFirst().orElseThrow();
            existing = new RequirementConditionOverride(
                    standardVersionId, requirementSetVersion, request.requirementId(),
                    request.conditionText(), extracted.role(),
                    com.app.docreview.retrievalcomparison.extraction.prompt.ExtractionPrompt.PROMPT_VERSION,
                    request.reviewedRole(), request.reviewStatus(),
                    request.authoriseExclusion(), request.reviewerNotes(),
                    request.reviewedBy(), batch,
                    // No source file: this decision came from the Admin UI, and naming a file that does
                    // not exist would make the provenance misleading rather than merely sparse.
                    null, null);
        } else {
            existing.revise(request.reviewedRole(), request.reviewStatus(),
                    request.authoriseExclusion(), request.reviewerNotes(),
                    request.reviewedBy(), batch);
        }
        var saved = overrideRepository.save(existing);

        log.info("Condition review recorded for {} in {} by {}: role={} status={} mayExclude={}",
                request.requirementId(), requirementSetVersion, request.reviewedBy(),
                saved.getReviewedRole(), saved.getReviewStatus(), saved.isCanDriveNotApplicable());

        // Re-resolved through the overlay rather than echoing the request, so the caller sees what a
        // comparison would now see — including exclusion authority WITHHELD when the four conditions
        // were not met. Echoing the request back would report an authority the system did not grant.
        var effective = effectiveConditions.resolve(
                standardVersionId, requirementSetVersion, request.requirementId(),
                applicability.effectiveConditions());
        return effective.stream()
                .filter(e -> normalise(e.condition().text()).equals(normalise(request.conditionText())))
                .findFirst()
                .map(e -> ResponseEntity.ok(new ConditionInspection(
                        request.requirementId(), e.condition().text(), e.condition().role(),
                        e.effectiveRole(), e.source(), e.canDriveNotApplicable(),
                        e.reviewerNotes(), e.overrideBatchId(),
                        requirement.getNormalizedRequirement(),
                        e.condition().conditionSourceSpanIds())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** One reviewer decision about one condition. */
    public record ConditionReviewRequest(
            String requirementId,
            String conditionText,
            ConditionRole reviewedRole,
            ConditionReviewStatus reviewStatus,
            boolean authoriseExclusion,
            String reviewerNotes,
            String reviewedBy) {
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Condition text compared on whitespace and case, so a re-wrapped paste still matches. */
    private static String normalise(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** A batch id fragment safe to store and read back. */
    private static String safe(String actor) {
        if (actor == null || actor.isBlank()) {
            return "unknown";
        }
        return actor.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
