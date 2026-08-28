package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.retrievalcomparison.comparison.application.RequirementComparisonService;
import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import com.app.docreview.retrievalcomparison.comparison.validation.ComparisonOutputValidator;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import com.app.docreview.retrievalcomparison.indexing.VectorIndexingService;
import com.app.docreview.retrievalcomparison.llm.litellm.LiteLlmProperties;
import com.app.docreview.retrievalcomparison.retrieval.document.AssessmentContext;
import com.app.docreview.retrievalcomparison.retrieval.fusion.ScoreFusion;
import com.app.docreview.retrievalcomparison.retrieval.requirement.EvidenceRetrievalService;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one comparison job. Architecture §20.2, §16, §18.
 *
 * <pre>
 *   load spans -> resolve standards -> load requirement sets
 *     -> per requirement: retrieve evidence, compare, validate
 *     -> consolidate findings -> report
 * </pre>
 *
 * <p><strong>§18.4 is the governing rule: one failed requirement must not discard the others.</strong>
 * Every comparison is therefore wrapped individually, and a failure is recorded as a unit error while
 * the loop continues. A job where 197 of 200 requirements succeeded reports
 * {@link JobState#PARTIALLY_COMPLETED} with the 197 findings — collapsing that to FAILED would throw
 * away valid, already-paid-for work, and calling it COMPLETED would hide that three controls were
 * never evaluated.
 *
 * <p>Requirements are loaded from the persisted set and never re-extracted (§12.1, §39 rule 19).
 *
 * <p><strong>Never throws to the caller.</strong> It runs on the executor after the 202 was sent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComparisonJobRunner {

    private final EvidenceSpanReader spanReader;
    private final StandardRequirementRepository requirementRepository;
    private final RequirementSetVersionRepository setVersionRepository;
    private final EvidenceRetrievalService retrievalService;
    private final RequirementComparisonService comparisonService;
    private final ComparisonResultWriter resultWriter;
    /** Decides and records applicability before any model call (§12.5). */
    private final com.app.docreview.retrievalcomparison.comparison.application.RequirementApplicabilityCoordinator applicabilityCoordinator;
    /** Builds the coverage ledger that makes "no finding" interpretable (§17.6). */
    private final com.app.docreview.retrievalcomparison.comparison.application.CoverageLedgerService coverageLedgerService;
    private final VectorIndexingService indexingService;
    private final com.app.docreview.retrievalcomparison.retrieval.document.StandardSelectionService standardSelection;
    /**
     * Infers the document's context so automatic selection ranks the same way the selection screen
     * does. Without it the two paths could pick different standards from identical inputs.
     */
    private final com.app.docreview.retrievalcomparison.llm.DocumentContextModelGateway documentContext;
    private final LiteLlmProperties properties;
    /** Read for the guidance digest recorded on the result (§19.2). */
    private final com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfigProvider;
    /**
     * Passages used to characterise the uploaded document for standard selection.
     *
     * <p>Enough to establish subject matter, few enough that shared boilerplate does not dominate.
     */
    private static final int PROFILE_SPAN_LIMIT = 40;

    /**
     * Standards auto-selected when the reviewer picked none: exactly the best-scoring one.
     *
     * <p>ONE, deliberately. Auto-selection is a fallback for a reviewer who did not choose, so it has
     * to behave like the choice they would most likely have made — the single most relevant standard —
     * not like a request to review against everything that scored close.
     *
     * <p>Three produced a measurably worse review: 118 findings instead of 65, with the same control
     * (CHG-01) appearing three times from three standards. A reviewer cannot tell whether that is
     * three real gaps or one gap counted thrice, and the duplication is an artefact of selection
     * rather than anything about the document.
     *
     * <p>The near-tie gate in {@code StandardSelectionService} still applies and still cannot widen
     * this: a runner-up within a few percent is reported as a REJECTED candidate with its score, so
     * a close call stays visible and the reviewer can select it explicitly (§15.1).
     */
    private static final int MAX_AUTO_SELECTED_STANDARDS = 1;

    private final WorkerJobRepository jobs;
    private final JobCallbackSender callbackSender;

    @Value("${docreview.jobs.max-requirements-per-comparison:500}")
    private int maxRequirements;

    /**
     * Requirements evaluated at once WITHIN one comparison. Architecture §10.5.
     *
     * <p>4 by default. Each unit is a chain of proxied model calls that is almost entirely wait, so
     * this is the difference between a 3-minute comparison and a 50-second one. The bound exists
     * because the ceiling is a shared proxy's rate limit: too high converts throughput into 429s,
     * which §18.4 surfaces as controls that could not be evaluated — a worse outcome than waiting.
     *
     * <p>Multiplies with {@code JOB_WORKER_THREADS}: total in-flight model calls for this service is
     * the product, so raise them together deliberately rather than either one alone.
     */
    @Value("${docreview.jobs.comparison-parallelism:4}")
    private int comparisonParallelism;

    public void run(WorkerJob job) {
        if (job.terminal()) {
            log.info("Job {} already terminal ({}); resending callback only",
                    job.getId(), job.getState());
            sendCallback(job, job.getState(), job.getFailureReason(), List.of(), Map.of());
            return;
        }

        try {
            advance(job, "RETRIEVING_REQUIREMENT_EVIDENCE");

            List<EvidenceSpan> processSpans = spanReader.allForVersion(job.getDocumentVersionId());
            if (processSpans.isEmpty()) {
                // §17.5: an unparsed document justifies neither satisfaction nor absence. Failing
                // here is correct — proceeding would classify every requirement MISSING and read as
                // total non-compliance.
                fail(job, "No evidence spans exist for this document version. "
                        + "Document processing must complete before comparison.");
                return;
            }

            var standardVersionIds = job.getStandardVersionIds();
            if (standardVersionIds.isEmpty()) {
                // Automatic selection (§15.1). An empty selection is a request to find the relevant
                // standards, not an error: a reviewer uploading a procedure usually does not know
                // which policy governs it, and that is the question the tool exists to answer.
                standardVersionIds = autoSelectStandards(job, processSpans);
                if (standardVersionIds.isEmpty()) {
                    // Distinct from "nothing selected": the search RAN and found nothing above the
                    // relevance floor. Comparing anyway would measure the document against an
                    // unrelated policy and report the mismatches as compliance gaps.
                    fail(job, "No standard in the library was relevant enough to compare against. "
                            + "Index a governing standard, or select one explicitly.");
                    return;
                }
                log.info("Auto-selected {} standard(s) for comparison {}",
                        standardVersionIds.size(), job.getComparisonId());
            } else {
                // EXPLICIT selection still needs the document type for applicability (§12.5).
                //
                // The type was previously resolved only inside autoSelectStandards, so on the REVIEW
                // path — where the reviewer picks the standards, which is the primary path — it was
                // always null. Every conditional requirement then degraded to UNCERTAIN: safe, in that
                // nothing was wrongly excluded, but the applicability feature was inert exactly where
                // it is most used.
                resolveDocumentType(job, processSpans);
            }

            var loaded = loadRequirements(standardVersionIds);
            var requirements = loaded.evaluated();
            if (requirements.isEmpty()) {
                fail(job, "The selected standards have no validated requirements to compare. "
                        + "Index the standard versions before comparing against them.");
                return;
            }

            // §20.2: the uploaded document's chunks must be embedded and indexed BEFORE any
            // requirement-level retrieval, because retrieval searches the process-chunk collection.
            // Without this the collection does not exist and every search fails — which §18.4 then
            // records as a per-requirement failure, producing a comparison with zero findings that
            // looks like a model problem rather than a missing index.
            advance(job, "GENERATING_EMBEDDINGS");
            var chunks = spanReader.chunksForVersion(job.getDocumentVersionId());
            if (chunks.isEmpty()) {
                fail(job, "No semantic chunks exist for this document version. "
                        + "Document processing must complete before comparison.");
                return;
            }
            indexingService.indexProcessChunks(
                    job.getDocumentVersionId(), job.getComparisonId(), chunks,
                    // Retention is a configured policy (§13.2); null means "keep until the document
                    // is deleted", which is what deleteProcessVectors handles.
                    null);

            job.setTotalUnits(requirements.size());
            advance(job, "EVALUATING_REQUIREMENT");

            // Captured for the §19.2 snapshot. Read from the loaded requirement set rather than from
            // this service's current configuration: a set indexed under an older chunking strategy
            // must be reported as such, or the comparison claims a provenance it does not have.
            String chunkingStrategyVersion = chunkingStrategyOf(requirements);
            String comparisonModelAlias = properties.models().comparison();

            var outcomes = evaluateConcurrently(job, requirements, processSpans, chunks.size());

            // Collected in REQUIREMENT ORDER, not completion order. Findings are consolidated and
            // then persisted, and §19.2 requires re-running a comparison to be reproducible — a
            // result list whose order depended on which model call returned first would differ
            // between two runs over identical inputs.
            List<Finding> findings = new ArrayList<>();
            List<String> unitErrors = new ArrayList<>();
            var failedRequirementIds = new java.util.LinkedHashSet<String>();
            for (var outcome : outcomes) {
                if (outcome.finding() != null) {
                    findings.add(outcome.finding());
                } else if (outcome.unitError() != null) {
                    unitErrors.add(outcome.unitError());
                    // Identity, not just a count: the ledger has to name which control went untested.
                    if (outcome.requirementId() != null) {
                        failedRequirementIds.add(outcome.requirementId());
                    }
                }
            }

            advance(job, "GENERATING_FINDINGS");
            var consolidated = comparisonService.consolidate(findings);
            resultWriter.write(job.getComparisonId(), job.getDocumentVersionId(), consolidated);

            // Coverage ledger, built from the requirement INVENTORY rather than the findings (§17.6).
            //
            // This is what makes "no finding" interpretable. Counting findings cannot detect a
            // requirement that was never evaluated, because such a requirement leaves no trace in the
            // findings — and a reader treats its absence as compliance.
            // NOT_APPLICABLE requirements become SKIPPED ledger rows with their reason, which is what
            // keeps them VISIBLE and stops them counting as PENDING. Without this an excluded
            // requirement would either vanish from the ledger — silently shrinking the review — or
            // block completion forever as pending work nobody will ever do (item 10, item 14).
            var applicabilityDecisions = outcomes.stream()
                    .map(UnitOutcome::applicability)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            var skipped = applicabilityCoordinator.skippedForLedger(applicabilityDecisions);

            // The ledger is built over EVERY expected requirement, including the ones the cap removed.
            //
            // Previously it received only the survivors, so a capped run reported 120/120 EVALUATED,
            // "Not evaluated: 0" and COMPLETED while 67 requirements had been silently dropped — and a
            // standard the reviewer had explicitly selected showed an empty tab with no explanation.
            // The dropped ones have no finding and no recorded failure, so they land as PENDING, which
            // is the one state that blocks completion (§18.4). That is the correct reading: they were
            // expected and never attempted.
            var expected = new ArrayList<>(requirements);
            expected.addAll(loaded.dropped());

            var cappedReason = new HashMap<String, String>();
            for (var requirement : loaded.dropped()) {
                cappedReason.put(requirement.getRequirementId(),
                        "Not evaluated: this comparison spans more requirements than the "
                                + maxRequirements + "-requirement limit for a single run. Compare "
                                + "against fewer standards at once to cover it.");
            }

            var ledger = coverageLedgerService.build(
                    job.getComparisonId(), expected, consolidated, failedRequirementIds, skipped,
                    cappedReason);

            // PERSISTED before the terminal state is decided below, so a reviewer told
            // "partially completed" can always find out which requirements that refers to. Computing
            // it and discarding it left the platform knowing something the reviewer could not see.
            resultWriter.writeLedger(ledger);

            // Partial when anything failed to evaluate. The distinction is visible to the user by
            // design (§21, §18.4) — a comparison missing three controls is not a complete review.
            //
            // The LEDGER is authoritative, not the unit-error list. An empty error list with pending
            // entries means requirements were dropped before evaluation was even attempted — a
            // silent gap the error list cannot see, and precisely the state that must not be reported
            // COMPLETED.
            var terminal = unitErrors.isEmpty() && ledger.isComplete()
                    ? JobState.COMPLETED
                    : JobState.PARTIALLY_COMPLETED;

            if (unitErrors.isEmpty() && !ledger.isComplete()) {
                log.warn("Comparison {} had no unit errors but {} untested requirement(s); "
                                + "reporting PARTIALLY_COMPLETED",
                        job.getComparisonId(), ledger.untestedCount());
            }

            job.setState(terminal);
            job.setProgressState(terminal == JobState.COMPLETED ? "COMPLETED" : "PARTIALLY_COMPLETED");
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobs.save(job);

            // §19.2 / §39 rule 30: report every version that produced this result. Reported from
            // HERE rather than assumed by review-core, because only this service knows which alias,
            // prompt, and schema actually ran — and a comparison attributed to the wrong versions is
            // worse than one with none, since it looks reproducible and is not.
            var results = new HashMap<String, String>();
            results.put(JobCallback.RESULT_FINDING_COUNT, String.valueOf(consolidated.size()));
            // Coverage travels with the findings so review-core can present "27 of 30 evaluated"
            // rather than a bare finding count (§17.6).
            results.put(JobCallback.RESULT_REQUIREMENTS_EXPECTED,
                    String.valueOf(ledger.expectedCount()));
            results.put(JobCallback.RESULT_REQUIREMENTS_EVALUATED,
                    String.valueOf(ledger.evaluatedCount()));
            results.put(JobCallback.RESULT_REQUIREMENTS_UNTESTED,
                    String.valueOf(ledger.untestedCount()));
            results.put(JobCallback.RESULT_COMPARISON_MODEL_ALIAS, comparisonModelAlias);
            // Includes the guidance digest, so this matches what the gateway reported per call and
            // a stored comparison resolves the instructions it actually ran under (§19.2).
            String guidance = aiConfigProvider.current().comparisonInstructions();
            results.put(JobCallback.RESULT_COMPARISON_PROMPT_VERSION,
                    com.app.docreview.retrievalcomparison.llm.PromptVersions.forGuidance(
                            ComparisonPrompt.PROMPT_VERSION, guidance));
            // Only when present: an absent key keeps the payload small for the common case, and
            // review-core leaves the field untouched rather than writing an empty string.
            if (guidance != null && !guidance.isBlank()) {
                results.put(JobCallback.RESULT_COMPARISON_GUIDANCE, guidance);
            }
            results.put(JobCallback.RESULT_COMPARISON_SCHEMA_VERSION,
                    ComparisonOutputValidator.SCHEMA_VERSION);
            results.put(JobCallback.RESULT_RETRIEVAL_STRATEGY_VERSION, ScoreFusion.STRATEGY_VERSION);
            results.put(JobCallback.RESULT_INDEX_VERSION, VectorIndexingService.INDEX_SCHEMA_VERSION);

            // Chunking strategy lives on the requirement-set version, not on the requirement's
            // extraction provenance, so it is read from the set that was actually loaded.
            var compatibility = indexingService.compatibility(chunkingStrategyVersion);
            results.put(JobCallback.RESULT_EMBEDDING_MODEL_ALIAS,
                    String.valueOf(compatibility.embeddingModelAlias()));
            results.put(JobCallback.RESULT_EMBEDDING_DIMENSIONS,
                    String.valueOf(compatibility.embeddingDimensions()));
            if (!requirements.isEmpty()) {
                results.put(JobCallback.RESULT_REQUIREMENT_SET_VERSION,
                        requirements.get(0).getRequirementSetVersion());
            }

            // ATTEMPTED minus failed, so the reported count is what was actually evaluated.
            //
            // In-flight progress counts ATTEMPTS, because attempts are the honest signal that a run
            // is advancing. But a TERMINAL count of attempts contradicts the failure message beside
            // it: the UI showed "1 of 15 requirements could not be evaluated" directly above
            // "15/15 evaluated", which reads as a system fault rather than a partial result.
            //
            // Every requirement produces exactly one outcome, so the attempt count is the outcome
            // count — no separate counter to drift out of step with the fan-out.
            int succeeded = Math.max(0, outcomes.size() - unitErrors.size());
            job.setCompletedUnits(succeeded);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);

            log.info("Comparison {} evaluated {}/{} requirements, {} findings, {} failures",
                    job.getComparisonId(), succeeded, requirements.size(),
                    consolidated.size(), unitErrors.size());

            sendCallback(job, terminal,
                    unitErrors.isEmpty() ? null
                            : unitErrors.size() + " of " + requirements.size()
                                    + " requirements could not be evaluated.",
                    // Bounded: hundreds of errors would make the callback unusable and the job
                    // record enormous.
                    unitErrors.stream().limit(50).toList(),
                    results);

        } catch (Exception e) {
            fail(job, "Comparison failed during "
                    + (job.getProgressState() == null ? "retrieval" : job.getProgressState()) + ".");
            log.error("Comparison job {} failed for {}: {}",
                    job.getId(), job.getComparisonId(), e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Loads the material requirements of each selected standard's current requirement set.
     *
     * <p>Only material requirements (§12.2): an advisory statement is never compared, so evaluating
     * one is spend with no possible finding. Bounded by {@code maxRequirements} because §15.5 forbids
     * unbounded fan-out — the cap is logged when it bites, so a truncated run is never silent.
     */
    private LoadedRequirements loadRequirements(List<String> standardVersionIds) {
        List<StandardRequirementDocument> all = new ArrayList<>();

        for (String standardVersionId : standardVersionIds) {
            // VALIDATED only. §12.8 withholds a NEEDS_REVIEW set from publication, and comparing
            // against unverified controls would produce findings nobody should act on.
            var latestSet = setVersionRepository
                    .findFirstByStandardVersionIdAndValidationStatusOrderByCreatedAtDesc(
                            standardVersionId,
                            StandardRequirementDocument.ValidationStatus.VALIDATED);
            if (latestSet.isEmpty()) {
                log.warn("Standard version {} has no VALIDATED requirement set; skipping. "
                        + "An extraction that needs review is not comparable (§12.8).",
                        standardVersionId);
                continue;
            }
            all.addAll(requirementRepository
                    .findByStandardVersionIdAndRequirementSetVersionAndMaterial(
                            standardVersionId, latestSet.get().getRequirementSetVersion(), true));
        }

        if (all.size() <= maxRequirements) {
            return new LoadedRequirements(all, List.of());
        }

        // ROUND-ROBIN, NOT sequential truncation.
        //
        // `subList(0, cap)` took requirements in standard order, so a three-standard comparison of
        // 84 + 44 + 59 against a cap of 120 gave the first standard all 84, the second 36 of 44, and
        // the third NOTHING. The reviewer saw an empty tab for a standard they had explicitly
        // selected, with no explanation — and the cap is a budget, not a statement that later
        // standards matter less.
        //
        // Interleaving spends the budget evenly, so every selected standard is represented and a
        // partial result is partial everywhere rather than complete in one place and absent in
        // another.
        var byStandard = new LinkedHashMap<String, List<StandardRequirementDocument>>();
        for (var requirement : all) {
            byStandard.computeIfAbsent(requirement.getStandardVersionId(), k -> new ArrayList<>())
                    .add(requirement);
        }

        var kept = new ArrayList<StandardRequirementDocument>(maxRequirements);
        var queues = new ArrayList<>(byStandard.values());
        int index = 0;
        while (kept.size() < maxRequirements) {
            boolean tookAny = false;
            for (var queue : queues) {
                if (index < queue.size()) {
                    kept.add(queue.get(index));
                    tookAny = true;
                    if (kept.size() == maxRequirements) {
                        break;
                    }
                }
            }
            if (!tookAny) {
                break;
            }
            index++;
        }

        // The DROPPED ones, returned rather than discarded. Previously the cap truncated the list
        // before the coverage ledger was built, so the ledger only ever saw the survivors: it
        // reported 120/120 EVALUATED, "Not evaluated: 0", and COMPLETED while 67 requirements had
        // been silently removed. A clean-looking report that is wrong is exactly what §17.6 exists to
        // prevent, so these now become explicit ledger rows.
        var keptIds = kept.stream()
                .map(StandardRequirementDocument::getId)
                .collect(java.util.stream.Collectors.toSet());
        var dropped = all.stream().filter(r -> !keptIds.contains(r.getId())).toList();

        log.warn("Comparison spans {} requirements; capping at {} (§15.5). {} requirements will NOT "
                        + "be evaluated and are recorded as untested coverage rows.",
                all.size(), maxRequirements, dropped.size());
        return new LoadedRequirements(kept, dropped);
    }

    /**
     * What a comparison will evaluate, and what the cap excluded.
     *
     * @param evaluated the requirements to compare, interleaved across standards
     * @param dropped   requirements the cap removed. Carried so the coverage ledger can record them
     *                  as untested — their absence from the findings must never read as compliance.
     */
    private record LoadedRequirements(List<StandardRequirementDocument> evaluated,
                                      List<StandardRequirementDocument> dropped) {
    }

    /**
     * Reads the chunking strategy from the loaded requirement sets.
     *
     * <p>Returns null when the sets disagree, rather than picking one: a comparison spanning standards
     * indexed under different chunking strategies has no single provenance, and §13.4 says such
     * indexes are not comparable. Reporting one of them would be a false claim about the other.
     */
    private String chunkingStrategyOf(List<StandardRequirementDocument> requirements) {
        var distinct = requirements.stream()
                .map(r -> setVersionRepository
                        .findFirstByStandardVersionIdAndValidationStatusOrderByCreatedAtDesc(
                                r.getStandardVersionId(),
                                StandardRequirementDocument.ValidationStatus.VALIDATED)
                        .map(RequirementSetVersionDocument::getChunkingStrategyVersion)
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        if (distinct.size() > 1) {
            log.warn("Comparison spans {} different chunking strategies {}; recording none, because "
                    + "indexes built under different chunking are not comparable (§13.4)",
                    distinct.size(), distinct);
            return null;
        }
        return distinct.isEmpty() ? null : distinct.get(0);
    }

    private void fail(WorkerJob job, String reason) {
        job.setState(JobState.FAILED);
        job.setProgressState("FAILED");
        job.setFailureReason(reason);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        sendCallback(job, JobState.FAILED, reason, List.of(), Map.of());
    }

    /**
     * Finds the standards a document should be measured against. Architecture §15.1.
     *
     * <p>The profile is built from the document's own leading passages rather than its whole text: a
     * standard is selected on what the procedure is ABOUT, and embedding forty pages dilutes that
     * signal with boilerplate — approval tables, revision history, distribution lists — that every
     * procedure shares and which therefore distinguishes nothing.
     *
     * <p>Returns AT MOST ONE standard — the best scorer — even when runners-up tie with it exactly.
     * Auto-selection substitutes for a choice the reviewer did not make, and the useful substitute is
     * the single most relevant standard; comparing against several turns one gap into several findings
     * that differ only by which policy they cite. A tie means the ranking cannot separate them, which
     * is a reason for the reviewer to pick, not a reason to review against both.
     *
     * <p>Still thresholded inside {@code StandardSelectionService}, so a document that matches nothing
     * relevant selects nothing rather than the least-bad option. Rejected candidates are returned with
     * their scores, so a near-tie remains visible and selectable by hand (§15.1).
     */
    private List<String> autoSelectStandards(WorkerJob job, List<EvidenceSpan> processSpans) {
        advance(job, "SEARCHING_STANDARDS");

        String profile = processSpans.stream()
                .map(EvidenceSpan::exactText)
                .filter(text -> text != null && !text.isBlank())
                .limit(PROFILE_SPAN_LIMIT)
                .collect(java.util.stream.Collectors.joining(" "));

        if (profile.isBlank()) {
            return List.of();
        }

        // Appended exactly as StandardCandidateController does, so the standards a reviewer was shown
        // are the ones this job selects. A preview computed differently from the run is worse than no
        // preview: it would recommend one standard and compare against another.
        String guidance = job.getGuidance();
        if (guidance != null && !guidance.isBlank()) {
            profile = profile + " " + guidance.trim();
        }

        // Retrieve, infer, rank — the SAME sequence the candidate endpoint uses.
        //
        // Without this the two paths diverge: the reviewer is shown candidates ranked with an inferred
        // department, then starting an automatic comparison re-selects with a neutral weight and can
        // pick a different standard than the one displayed at the top. The reviewer would have no way
        // to see that happened.
        var hits = standardSelection.retrieve(profile);
        if (hits.isEmpty()) {
            return List.of();
        }

        var inferred = documentContext.infer(profile, standardSelection.vocabularyOf(hits));
        // Retained on the job so requirement-level applicability uses the SAME answer the ranking
        // used, rather than inferring a second time and possibly disagreeing with itself.
        if (inferred.available() && inferred.documentType() != null) {
            job.setDocumentType(inferred.documentType());
        }
        var context = inferred.available()
                ? new AssessmentContext(inferred.department(), inferred.standardFamily(),
                        inferred.documentType(), guidance)
                : AssessmentContext.none();

        return standardSelection.rank(hits, MAX_AUTO_SELECTED_STANDARDS, context).stream()
                .filter(com.app.docreview.contracts.retrieval.StandardCandidate::selected)
                .map(com.app.docreview.contracts.retrieval.StandardCandidate::standardVersionId)
                .toList();
    }

    /**
     * Records a stage locally AND reports it to review-core. Architecture §21, §22.
     *
     * <p>Both halves are needed. The local write is what a recovery sweep reads after a restart; the
     * report is what the UI can see, because §22 keeps this service's database private. Writing only
     * locally is what left a ten-minute comparison showing the stage it had at dispatch.
     */
    /**
     * One requirement's result: exactly one of {@code finding} and {@code unitError} is non-null.
     *
     * <p>{@code requirementId} is carried even on failure, so the coverage ledger can say WHICH
     * requirement went untested. Without it a failure is only a count, and §17.6 needs the identity —
     * "three controls failed" does not tell a reviewer whether the one they care about was tested.
     */
    private record UnitOutcome(
            String requirementId,
            Finding finding,
            String unitError,
            /**
             * The applicability decision, always present.
             *
             * <p>Carried even for a successful comparison so the ledger and the result writer see one
             * decision per requirement. A NOT_APPLICABLE outcome has a null finding AND a null
             * unitError — it is neither a result nor a failure, which is why it needs its own field
             * rather than being inferred from the other two being empty.
             */
            com.app.docreview.contracts.comparison.ApplicabilityDecision applicability) {
    }

    /**
     * Evaluates every requirement, several at a time. Architecture §10.5, §18.4, §19.2.
     *
     * <p><strong>Why concurrency here and not more worker threads.</strong> Each unit is a chain of
     * network calls — an embedding, a vector search, a comparison — and spends nearly all its wall
     * time waiting. A 41-requirement comparison took 3m21s at ~4.9s each, essentially all of it idle.
     * Raising {@code JOB_WORKER_THREADS} would instead run more comparisons at once, which does not
     * help the reviewer waiting on THIS one.
     *
     * <p><strong>Bounded, and deliberately modest.</strong> The ceiling is a shared organizational
     * proxy's rate limit, not local CPU (§10.5): overshooting turns 429s into unit errors, so a
     * comparison would report controls as unevaluated because we asked too fast. The bound is
     * per-comparison, so total in-flight calls stay {@code worker-threads × this}.
     *
     * <p>Results come back in REQUIREMENT order regardless of completion order, which §19.2 requires.
     * Virtual threads are not used: these tasks are the thing being rate-limited, so an unbounded
     * executor would defeat the point of bounding them.
     */
    /**
     * Resolves the document's type for the EXPLICIT-selection path. Architecture §12.5, §15.1.
     *
     * <p>The automatic path infers this as a side effect of ranking candidates. The explicit path does
     * no ranking, so without this it had no document type at all — and every conditional requirement
     * degraded to UNCERTAIN on the primary reviewer path.
     *
     * <p>One inference per JOB, not per requirement: the answer is a property of the document, so
     * recomputing it per requirement would pay for the same call repeatedly and risk disagreeing with
     * itself between requirements.
     *
     * <p>Best-effort. A failure leaves the type null, which resolves conditional requirements to
     * UNCERTAIN — they are still compared, so a failed inference costs scope precision rather than
     * coverage.
     */
    private void resolveDocumentType(WorkerJob job, List<EvidenceSpan> processSpans) {
        try {
            String profile = processSpans.stream()
                    .map(EvidenceSpan::exactText)
                    .filter(text -> text != null && !text.isBlank())
                    .limit(PROFILE_SPAN_LIMIT)
                    .collect(java.util.stream.Collectors.joining(" "));
            if (profile.isBlank()) {
                return;
            }
            // The vocabulary comes from retrieved candidates, so the inferred type is one that exists
            // on a real standard rather than free text (see DocumentContextModelGateway).
            var hits = standardSelection.retrieve(profile);
            if (hits.isEmpty()) {
                return;
            }
            var inferred = documentContext.infer(profile, standardSelection.vocabularyOf(hits));
            if (inferred.available() && inferred.documentType() != null) {
                job.setDocumentType(inferred.documentType());
                log.info("Resolved document type for comparison {} (explicit selection)",
                        job.getComparisonId());
            }
        } catch (Exception e) {
            // Class name only (§35). Never fatal: an unresolved type degrades scope precision, and
            // failing the comparison over it would be far worse.
            log.warn("Could not resolve document type for {} ({}); conditional requirements will be "
                            + "reported UNCERTAIN", job.getComparisonId(), e.getClass().getSimpleName());
        }
    }

    private List<UnitOutcome> evaluateConcurrently(
            WorkerJob job,
            List<StandardRequirementDocument> requirements,
            List<EvidenceSpan> processSpans,
            int chunkCount) {

        int parallelism = Math.min(comparisonParallelism, Math.max(1, requirements.size()));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(parallelism, runnable -> {
            var thread = new Thread(runnable, "cmp-unit");
            // Daemon: a stuck provider call must not keep the JVM alive at shutdown.
            thread.setDaemon(true);
            return thread;
        });

        // Counts completions for progress, which is why it is atomic rather than a loop variable:
        // several threads finish concurrently and the UI needs a total, not a per-thread count.
        var completed = new java.util.concurrent.atomic.AtomicInteger();

        try {
            var futures = new ArrayList<java.util.concurrent.Future<UnitOutcome>>(requirements.size());
            for (var requirement : requirements) {
                futures.add(executor.submit(() -> {
                    try {
                        return evaluateOne(job, requirement, processSpans, chunkCount);
                    } finally {
                        // Reported from the worker thread as each unit lands, so the count advances
                        // smoothly rather than in bursts at pool boundaries.
                        reportProgress(job, "EVALUATING_REQUIREMENT", completed.incrementAndGet());
                    }
                }));
            }

            var outcomes = new ArrayList<UnitOutcome>(requirements.size());
            for (int i = 0; i < futures.size(); i++) {
                var requirement = requirements.get(i);
                try {
                    outcomes.add(futures.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    // evaluateOne already converts its own failures, so reaching here means something
                    // escaped it. Recorded as a unit error rather than thrown: §18.4 forbids one
                    // requirement from discarding the other forty.
                    outcomes.add(unexpectedFailure(requirement, e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(unexpectedFailure(requirement, e));
                }
            }

            // Persisted once, after the fan-out. Saving per unit from several threads would have the
            // threads overwrite each other's copy of the job document.
            job.setCompletedUnits(completed.get());
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
            return outcomes;

        } finally {
            executor.shutdownNow();
        }
    }

    /** One requirement: retrieve, compare, and classify the result. Never throws (§18.4). */
    /**
     * Rebuilds and re-persists the coverage ledger after a targeted retry. Architecture §17.6.
     *
     * <p>Built from the CURRENT findings, so a recovered requirement moves from untested to evaluated.
     * Leaving the old ledger would report a requirement as never attempted while a finding for it
     * exists — the §17.6 contradiction inverted, and just as misleading.
     */
    public void rebuildCoverage(WorkerJob job) {
        var loaded = loadRequirements(job.getStandardVersionIds());
        var expected = new ArrayList<>(loaded.evaluated());
        expected.addAll(loaded.dropped());

        var findings = resultWriter.read(job.getComparisonId());
        // No failed set and no skip reasons: a requirement with no finding is simply still untested,
        // and inventing a reason here would assert a cause nobody established.
        var ledger = coverageLedgerService.build(
                job.getComparisonId(), expected, findings, java.util.Set.of(), Map.of());
        resultWriter.writeLedger(ledger);
    }

    /**
     * Re-evaluates ONE requirement that failed, without redoing the rest. Architecture §18.4, §19.2.
     *
     * <p><strong>Why targeted.</strong> A comparison over three standards is 187 model calls. When a
     * single requirement is rejected — the model's verdict contradicting the evidence it cited, which
     * is a per-call inconsistency and usually transient — repeating all 187 to recover one is 186 calls
     * of pure waste, and it produces a whole new comparison the reviewer then has to re-read.
     *
     * <p><strong>Runs the identical path.</strong> Same applicability decision, same retrieval, same
     * comparison and validation as {@link #evaluateOne}. A retry that took a shortcut would produce a
     * finding not comparable with its neighbours, which is worse than not retrying.
     *
     * <p>Returns empty when the retry fails again: the requirement stays untested and its coverage row
     * stays PENDING, which is the honest outcome. A retry is an attempt, not a guarantee.
     */
    public java.util.Optional<Finding> retryRequirement(WorkerJob job, String requirementId) {
        var processSpans = spanReader.allForVersion(job.getDocumentVersionId());
        if (processSpans.isEmpty()) {
            log.warn("Cannot retry {} for comparison {}: the document has no evidence spans",
                    requirementId, job.getComparisonId());
            return java.util.Optional.empty();
        }
        int chunkCount = spanReader.chunksForVersion(job.getDocumentVersionId()).size();

        // Loaded from the SAME sets the original run used, resolved the same way — the newest
        // VALIDATED set per standard. A retry against a different set would silently be measuring a
        // different requirement than the one that failed.
        var loaded = loadRequirements(job.getStandardVersionIds());
        var requirement = loaded.evaluated().stream()
                .filter(r -> requirementId.equals(r.getRequirementId()))
                .findFirst()
                .orElse(null);
        if (requirement == null) {
            log.warn("Cannot retry {} for comparison {}: not in the current requirement sets",
                    requirementId, job.getComparisonId());
            return java.util.Optional.empty();
        }

        var outcome = evaluateOne(job, requirement, processSpans, chunkCount);
        if (outcome.finding() == null) {
            // Counts and ids only, never requirement text (§35).
            log.info("Retry of {} for comparison {} did not produce a finding",
                    requirementId, job.getComparisonId());
            return java.util.Optional.empty();
        }

        // STAMPED as a retry. The comparison's header records the versions of its original run, so an
        // amended finding that carried no marker would be indistinguishable from one produced then —
        // and a reader comparing it against its neighbours could not tell why it differs.
        var stamped = withRetryProvenance(outcome.finding());
        resultWriter.appendFinding(stamped);
        log.info("Retried {} for comparison {}: {}",
                requirementId, job.getComparisonId(), stamped.status());
        return java.util.Optional.of(stamped);
    }

    /** Copies a finding with its retry provenance recorded. */
    private Finding withRetryProvenance(Finding f) {
        var provenance = new Finding.RetryProvenance(
                Instant.now(),
                ComparisonPrompt.PROMPT_VERSION,
                properties.models().comparison(),
                // The override batch that actually decided applicability, already resolved per
                // requirement — reused rather than re-derived so the two cannot disagree.
                f.applicabilityDecidedBy());
        return new Finding(f.findingId(), f.comparisonId(), f.standardVersionId(),
                f.requirementSetVersion(), f.requirementId(), f.parentControlId(),
                f.requirementTitle(), f.status(), f.gapType(), f.severity(),
                f.standardSpanIds(), f.processSpanIds(), f.nearestProcessSpanIds(),
                f.clauseResults(), f.reason(), f.recommendedAction(), f.confidence(),
                f.needsHumanReview(), f.applicabilityStatus(), f.applicabilityReason(),
                f.applicabilityDecidedBy(), provenance);
    }

    private UnitOutcome evaluateOne(
            WorkerJob job,
            StandardRequirementDocument requirement,
            List<EvidenceSpan> processSpans,
            int chunkCount) {
        try {
            // APPLICABILITY FIRST, before any retrieval or model call (§12.5, item 10).
            //
            // A requirement that does not apply must not reach the comparison model: the verdict would
            // be meaningless, it would cost a call, and whatever came back would have to be discarded.
            // Deciding here rather than inside buildFinding is what makes "the model is not invoked for
            // NOT_APPLICABLE" true rather than merely intended.
            var applicability = applicabilityCoordinator.decide(
                    requirement,
                    new com.app.docreview.retrievalcomparison.comparison.application
                            .ApplicabilityResolver.ConfirmedContext(job.getDocumentType()));

            if (!applicability.shouldCompare()) {
                // No finding, no ComparisonStatus — the requirement was never measured, and inventing
                // a verdict is exactly what keeping the two statuses separate exists to prevent.
                // The decision travels back so the ledger records a SKIPPED row with the reason, which
                // keeps the control VISIBLE in the results rather than vanishing from them.
                return new UnitOutcome(requirement.getRequirementId(), null, null, applicability);
            }

            var retrieved = retrievalService.retrieve(
                    requirement.toExtractedRequirement(), job.getDocumentVersionId(),
                    // totalChunks is the CHUNK count, not the span count: §15.4 records how many
                    // passages were searched, and spans are finer-grained than the retrieval unit.
                    processSpans, chunkCount, true, List.of());

            var outcome = comparisonService.compare(
                    job.getComparisonId(), job.getDocumentVersionId(),
                    requirement.getStandardVersionId(), requirement.getRequirementSetVersion(),
                    requirement.toExtractedRequirement(), requirement.getSeverity(),
                    retrieved.candidates(), retrieved.coverage(),
                    // The SAME decision that drove the skip gate above and the coverage ledger below.
                    // Passing it through is what makes the reviewer's finding show the governed answer:
                    // the comparer used to re-resolve applicability from the extraction alone, so a
                    // human-reviewed condition role never reached the report.
                    applicability);

            if (outcome.succeeded()) {
                return new UnitOutcome(requirement.getRequirementId(), outcome.finding(), null, applicability);
            }

            // §18.4: recorded and carried on. The requirement ID is included so a reviewer can see
            // WHICH control was not evaluated.
            //
            // SUMMARISED, not concatenated. The raw list is a validator trace — four repetitions of
            // "satisfied clause 4-2.who is not part of this requirement" told a reviewer nothing they
            // could act on, while burying the one fact that matters: this control was not assessed,
            // so check it by hand. The full list stays in the log for whoever is debugging the model.
            log.warn("Requirement {} failed validation: {}", requirement.getRequirementId(),
                    String.join("; ", outcome.errors()));
            return new UnitOutcome(requirement.getRequirementId(), null,
                    requirement.getRequirementId() + ": " + summarise(outcome.errors()),
                    applicability);

        } catch (Exception e) {
            return unexpectedFailure(requirement, e);
        }
    }

    /**
     * A requirement that failed for a reason we cannot describe to a reviewer.
     *
     * <p>The unit error keeps the TYPE only, because it is surfaced to the browser and a raw message
     * can quote document text (§10.5, §34). The LOG gets the message and the stack trace: without
     * them an IllegalStateException from anywhere in retrieval or validation is indistinguishable
     * from any other, and the operator is left with a type name and no line number.
     */
    private UnitOutcome unexpectedFailure(StandardRequirementDocument requirement, Throwable cause) {
        log.warn("Comparison of requirement {} failed: {}: {}",
                requirement.getRequirementId(),
                cause == null ? "unknown" : cause.getClass().getSimpleName(),
                cause == null ? "" : cause.getMessage(), cause);
        return new UnitOutcome(requirement.getRequirementId(), null, requirement.getRequirementId()
                + ": evaluation failed unexpectedly, so the result was rejected. "
                + "Review this control manually.",
                // APPLICABLE: reaching this point means comparison was attempted, which only happens
                // for a requirement that applied. Recording anything else would misreport the scope.
                com.app.docreview.contracts.comparison.ApplicabilityDecision.applicable(
                        requirement.getRequirementId(),
                        com.app.docreview.retrievalcomparison.comparison.application
                                .ApplicabilityResolver.RESOLVER_VERSION));
    }

    private void advance(WorkerJob job, String progressState) {
        job.setProgressState(progressState);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
        log.debug("Job {} -> {}", job.getId(), progressState);
        reportProgress(job, progressState, null);
    }

    /**
     * Sends one progress report, or nothing if this job has nowhere to send it.
     *
     * <p>Never throws: progress is a hint, and a comparison must not fail because a status update
     * could not be delivered (see {@code JobCallbackSender#sendProgress}).
     */
    private void reportProgress(WorkerJob job, String progressState, Integer completedUnits) {
        String callbackUrl = job.getCallbackUrl();
        String comparisonId = job.getComparisonId();
        if (callbackUrl == null || callbackUrl.isBlank() || comparisonId == null) {
            return;
        }
        callbackSender.sendProgress(
                callbackUrl + "/" + comparisonId + "/progress",
                new com.app.docreview.contracts.job.JobProgress(
                        job.getId(), JobType.RETRIEVAL_COMPARISON, progressState,
                        completedUnits, job.getTotalUnits()));
    }

    /**
     * Turns a validator trace into one sentence a reviewer can act on.
     *
     * <p>The raw errors are written for whoever is debugging the model: they name clause IDs, span
     * IDs, and schema rules. Surfacing them in the review UI produced lines like "satisfied clause
     * 4-2.who is not part of this requirement; satisfied clause 4-2.does is not part of this
     * requirement; …" — four near-identical strings whose only actionable content is that the control
     * went unassessed.
     *
     * <p>A reviewer needs exactly two things: that this control was NOT checked, and roughly why, so
     * they know whether to re-run or inspect it by hand. Categories, not verbatim text. The full list
     * is logged for diagnosis.
     */
    private String summarise(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "the model's response could not be validated. Review this control manually.";
        }
        // Ordered by severity: a fabricated citation matters more than a formatting mismatch, and a
        // response usually trips several rules at once.
        if (errors.stream().anyMatch(e -> e.contains("was not supplied as evidence"))) {
            return "the model cited evidence that was not supplied, so the result was rejected. "
                    + "Review this control manually.";
        }
        if (errors.stream().anyMatch(e -> e.contains("not part of this requirement"))) {
            return "the model's clause breakdown did not match this requirement, so the result was "
                    + "rejected. Review this control manually.";
        }
        if (errors.stream().anyMatch(e -> e.contains("requires") || e.contains("omits"))) {
            return "the model's verdict was inconsistent with the evidence it cited, so the result "
                    + "was rejected. Review this control manually.";
        }
        return "the model's response failed validation, so the result was rejected. "
                + "Review this control manually.";
    }

    private void sendCallback(WorkerJob job, JobState state, String failureReason,
                              List<String> unitErrors, Map<String, String> results) {
        var callback = new JobCallback(
                job.getOriginJobId(), JobType.RETRIEVAL_COMPARISON, job.getIdempotencyKey(),
                state, job.getProgressState(), job.getCompletedUnits(), job.getTotalUnits(),
                failureReason, unitErrors, results);

        if (callbackSender.send(job.getCallbackUrl(), callback)) {
            job.setCallbackDelivered(true);
            job.setUpdatedAt(Instant.now());
            jobs.save(job);
        }
    }
}
