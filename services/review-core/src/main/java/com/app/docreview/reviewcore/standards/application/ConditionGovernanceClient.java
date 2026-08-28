package com.app.docreview.reviewcore.standards.application;

import com.app.docreview.reviewcore.workflow.InternalRestClientFactory;
import com.app.docreview.reviewcore.workflow.JobDispatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads applicability-condition governance from retrieval-comparison. Architecture §3, §12.5, §19.2.
 *
 * <p><strong>Why a proxy rather than a direct browser call.</strong> §3 keeps the browser talking only
 * to review-core; the worker's {@code /internal} surface is not reachable from a session and carries no
 * user authorization. Fetching here also means the admin page inherits review-core's role checks
 * instead of re-implementing them.
 *
 * <p><strong>Read-only, deliberately.</strong> Editing a role is not exposed: overrides are imported as
 * a hashed review batch so every governed decision traces to an artifact a person signed off (§19.2). An
 * inline edit button would produce authoritative decisions with no reviewer, no rationale, and no batch
 * — which is exactly the provenance this model exists to keep.
 */
@Component
@Slf4j
public class ConditionGovernanceClient {

    private final RestClient retrievalComparison;

    public ConditionGovernanceClient(JobDispatchProperties properties,
                                     InternalRestClientFactory clientFactory) {
        this.retrievalComparison = clientFactory.create(
                properties.retrievalComparison().baseUrl());
    }

    /**
     * One applicability condition: what the model said, what review decided, and what governs.
     *
     * @param extractedRole         the model's classification, retained so the disagreement stays
     *                              visible — it is the only measure of extraction quality there is
     * @param canDriveNotApplicable whether this condition may remove a requirement from a review.
     *                              Independent of {@code effectiveRole}: a confirmed scope gate that
     *                              nobody authorised still cannot exclude
     */
    public record ConditionRow(
            String requirementId,
            String conditionText,
            String extractedRole,
            String effectiveRole,
            String source,
            boolean canDriveNotApplicable,
            String reviewerNotes,
            String overrideBatchId,
            boolean reclassified) {
    }

    /**
     * One applicability question nobody has ruled on yet.
     *
     * @param inconsistentlyExtracted the model gave this same phrase different roles in different
     *                                places, so its opinion on it carries little weight
     * @param truncated               the phrase looks like an extraction fragment, so the right fix may
     *                                be the extractor rather than a policy ruling
     */
    public record OpenQuestion(
            String conditionText,
            int occurrences,
            List<String> standardVersionIds,
            List<String> requirementIds,
            List<String> requirementTexts,
            List<String> extractedRoles,
            String reviewerNotes,
            String reviewBatchId,
            boolean inconsistentlyExtracted,
            boolean truncated) {
    }

    /**
     * The open applicability questions, most-recurring first.
     *
     * <p>Empty on failure. Nothing is blocked by this queue — every condition in it is already being
     * compared under uncertainty — so a worker outage must not break the page around it.
     */
    public List<OpenQuestion> policyOwnerQueue() {
        try {
            var rows = retrievalComparison.get()
                    .uri("/internal/requirements/policy-owner-queue")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OpenQuestion>>() {});
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("Could not read the policy-owner queue: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Which requirement set a comparison would use, as the worker reports it. */
    public record CurrentSet(String requirementSetVersion,
                             String requirementExtractionPromptVersion,
                             int requirementCount) {
    }

    /**
     * Resolves the live requirement set for a version, or null when none is comparable.
     *
     * <p><strong>Asked rather than assumed.</strong> review-core's own version record carries a
     * denormalised {@code requirementSetVersion} that goes stale — a re-extraction updates the worker
     * without writing back here, and it read {@code REQSET-V1} for every version long after V3 went
     * live. Resolving it from the owning service means the admin page cannot query a set that does not
     * exist and render the empty result as "nothing to review".
     */
    public CurrentSet currentSet(String standardVersionId) {
        try {
            return retrievalComparison.get()
                    .uri("/internal/requirements/sets/{standardVersionId}/current", standardVersionId)
                    .retrieve()
                    .body(CurrentSet.class);
        } catch (Exception e) {
            log.warn("Could not resolve current requirement set for {}: {}",
                    standardVersionId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Reads the conditions for one requirement set.
     *
     * <p>Returns empty on failure rather than throwing. This is an inspection panel: a worker that is
     * down should render an empty table in an otherwise working admin page, not break the page an
     * administrator is using to configure the very deployment that is failing.
     */
    public List<ConditionRow> conditions(String standardVersionId, String requirementSetVersion) {
        try {
            var rows = retrievalComparison.get()
                    .uri("/internal/requirements/sets/{standardVersionId}/{requirementSetVersion}"
                                    + "/conditions",
                            standardVersionId, requirementSetVersion)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ConditionRow>>() {});

            if (rows == null) {
                log.warn("Condition read for set {} returned no body", requirementSetVersion);
                return List.of();
            }
            return rows;

        } catch (Exception e) {
            // Type only: condition text is standard content, and an exception message can echo the
            // URI and body (§35).
            log.error("Failed to read conditions for set {}: {}",
                    requirementSetVersion, e.getClass().getSimpleName());
            return List.of();
        }
    }
}
