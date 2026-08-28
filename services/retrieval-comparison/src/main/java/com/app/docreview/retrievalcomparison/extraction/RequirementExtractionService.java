package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.extraction.CandidateCategory;
import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.extraction.candidate.CandidateDetector;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.RequirementSetVersionRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementDocument;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import com.app.docreview.retrievalcomparison.extraction.prompt.ExtractionPrompt;
import com.app.docreview.retrievalcomparison.extraction.validation.ExtractionValidator;
import com.app.docreview.retrievalcomparison.llm.RequirementExtractionModelGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates requirement extraction for one standard version. Architecture §12.
 *
 * <p>Runs ONCE per immutable standard version (§12.1) and the result is reused by every later
 * comparison. Re-extracting per comparison would be both wasteful and non-reproducible: two users
 * comparing against the same standard would be measured against different requirement sets.
 *
 * <p>The pipeline, and where each step's failure is contained:
 *
 * <pre>
 *   detect candidates      deterministic; a missed candidate is an unreportable requirement
 *   extract per candidate  one LLM call each; a failure is isolated to that candidate
 *   validate               §12.8; one retry, then NEEDS_REVIEW rather than publishing
 *   consolidate            rule 10; merges duplicates without losing source evidence
 *   persist                new requirement-set version, never overwriting the old one
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequirementExtractionService {

    private final CandidateDetector detector;
    private final RequirementExtractionModelGateway gateway;
    private final ExtractionValidator validator;
    private final SeverityResolver severityResolver;
    private final StandardRequirementRepository requirementRepository;
    private final RequirementSetVersionRepository setVersionRepository;
    private final RequirementIdAssigner idAssigner;

    /**
     * Extracts and publishes a requirement set.
     *
     * @param chunkingStrategyVersion recorded because chunking affects retrieval, and a comparison
     *                                run under different chunking is not comparable (§12.9)
     */
    public RequirementSetVersionDocument extract(String standardId, String standardVersionId,
                                                 String standardTitle,
                                                 List<EvidenceSpan> spans,
                                                 String chunkingStrategyVersion) {
        return extract(standardId, standardVersionId, standardTitle, spans,
                chunkingStrategyVersion, false);
    }

    /**
     * Extracts a set for EVALUATION ONLY, which no comparison can use. §12.9, §19.2.
     *
     * <p>Measures a prompt change against decisions a person already reviewed, without replacing them.
     * A normal extraction would land as VALIDATED and immediately become the live set — discarding the
     * review to regain a model guess, which is what the override model exists to prevent.
     *
     * <p>Identical extraction path, deliberately. The only difference is the terminal status: scoring a
     * run produced by a special-case code path would measure the harness, not the extractor.
     */
    public RequirementSetVersionDocument extractShadow(String standardId, String standardVersionId,
                                                       String standardTitle,
                                                       List<EvidenceSpan> spans,
                                                       String chunkingStrategyVersion) {
        return extract(standardId, standardVersionId, standardTitle, spans,
                chunkingStrategyVersion, true);
    }

    private RequirementSetVersionDocument extract(String standardId, String standardVersionId,
                                                  String standardTitle,
                                                  List<EvidenceSpan> spans,
                                                  String chunkingStrategyVersion,
                                                  boolean shadow) {

        String requirementSetVersion = nextSetVersion(standardVersionId);
        var run = new RequirementSetVersionDocument(
                standardVersionId, requirementSetVersion,
                // Model alias is read from the first extraction result rather than assumed, so the
                // record reflects what actually ran.
                "pending", ExtractionPrompt.PROMPT_VERSION, ExtractionValidator.SCHEMA_VERSION,
                CandidateDetector.DETECTOR_VERSION, chunkingStrategyVersion);

        var candidates = detector.detect(spans, nodeId -> null);
        run.setCandidateCount(candidates.size());

        // Span lookup for validation. Built once: rule 2 checks every returned ID against the spans
        // supplied for that candidate, and doing it per call would rebuild this repeatedly.
        Map<String, EvidenceSpan> spansById = new HashMap<>();
        spans.forEach(s -> spansById.put(s.spanId(), s));

        List<ExtractedRequirement> accepted = new ArrayList<>();
        // Sections that produced no requirement, surfaced so the author can tell an intentional
        // omission from a clause that was worded too weakly to be recognised (§12.2).
        List<String> skipped = new ArrayList<>();
        List<String> allErrors = new ArrayList<>();
        Set<String> seenRequirementIds = new HashSet<>();
        String modelAlias = null;
        long promptTokens = 0;
        long completionTokens = 0;

        for (RequirementCandidate candidate : candidates) {
            // Advisory, definition, example and rationale candidates are recorded by the detector
            // but not sent for extraction (§12.2): they are not evaluated, so an LLM call would be
            // spend with no possible finding.
            if (!evaluatable(candidate.category())) {
                log.debug("Skipping {} candidate {}", candidate.category(), candidate.candidateId());
                // Reported to the author, not only logged. A section classified as guidance or a
                // definition produces NO requirement, so it is silently absent from every future
                // comparison — and §12.2 makes that correct behaviour, not a bug. But an author who
                // intended a clause to be binding has no way to discover it was skipped unless we
                // say so. The message is informational: nothing failed.
                // QUOTES the text rather than naming a derived clause number. The number came from
                // the enclosing section heading, so a skipped paragraph inside "1 Purpose" was
                // labelled "Clause 1" — which points an author at a heading rather than at the
                // sentence that was actually dropped. The words are unambiguous; a derived number is
                // not.
                skipped.add("%s — read as %s, so no requirement was extracted: \"%s\"".formatted(
                        sectionLabel(candidate),
                        candidate.category().name().toLowerCase().replace('_', ' '),
                        excerpt(candidate.combinedText())));
                continue;
            }

            var result = callWithOneRetry(candidate, spansById, standardId, standardVersionId,
                    requirementSetVersion, standardTitle, seenRequirementIds);

            modelAlias = result.modelAlias();
            promptTokens += result.promptTokens();
            completionTokens += result.completionTokens();
            accepted.addAll(result.accepted());
            allErrors.addAll(result.errors());
        }

        // Rule 10: consolidate across ALL candidates. Requires the whole set, which is why it runs
        // here rather than inside the per-candidate validator.
        var consolidated = consolidate(accepted);

        // SHADOW wins over both outcomes. A shadow run that happened to validate cleanly must still be
        // unusable by a comparison — "it passed validation" is not the same claim as "it should replace
        // the reviewed set", and conflating them is how an evaluation run becomes a silent deployment.
        var status = shadow
                ? StandardRequirementDocument.ValidationStatus.SHADOW
                : allErrors.isEmpty()
                        ? StandardRequirementDocument.ValidationStatus.VALIDATED
                        : StandardRequirementDocument.ValidationStatus.NEEDS_REVIEW;

        var provenance = new StandardRequirementDocument.ExtractionProvenance(
                modelAlias == null ? "none" : modelAlias,
                ExtractionPrompt.PROMPT_VERSION, ExtractionValidator.SCHEMA_VERSION,
                CandidateDetector.DETECTOR_VERSION, Instant.now());

        List<StandardRequirementDocument> documents = consolidated.stream()
                .map(r -> new StandardRequirementDocument(
                        standardId, standardVersionId, requirementSetVersion, r,
                        severityResolver.resolve(r, null), provenance, status))
                .toList();
        requirementRepository.saveAll(documents);

        run.setRequirementCount(documents.size());
        run.setMaterialRequirementCount((int) documents.stream()
                .filter(StandardRequirementDocument::isMaterial).count());
        run.setRejectedCount(allErrors.size());
        run.setPromptTokens(promptTokens);
        run.setCompletionTokens(completionTokens);
        run.setValidationStatus(status);
        // Bounded: a set with hundreds of errors would otherwise store an unusable blob.
        //
        // Skipped sections are appended AFTER real errors so the actionable failures come first in
        // the list the UI renders.
        run.setValidationErrors(
                java.util.stream.Stream.concat(allErrors.stream(), skipped.stream())
                        .limit(50).toList());
        run.setSkippedCandidateCount(skipped.size());
        run.setCompletedAt(Instant.now());

        log.info("Requirement set {} for {}: {} candidates -> {} requirements ({} material), "
                        + "{} rejected, status {}",
                requirementSetVersion, standardVersionId, candidates.size(), documents.size(),
                run.getMaterialRequirementCount(), allErrors.size(), status);

        return setVersionRepository.save(run);
    }

    /**
     * Where a candidate sits, for author-facing messages.
     *
     * <p>Uses the SECTION HEADING, not the derived control id. The control id is parsed from the
     * enclosing heading, so it is identical for every paragraph in a section — naming it makes two
     * different skipped sentences look like the same location. The heading at least scopes the
     * search, and the quoted excerpt pins it exactly.
     */
    private String sectionLabel(RequirementCandidate candidate) {
        if (candidate.sectionPath() != null && !candidate.sectionPath().isEmpty()) {
            // Deepest heading: the immediate parent, not the document title.
            return candidate.sectionPath().get(candidate.sectionPath().size() - 1);
        }
        if (candidate.sourceControlId() != null && !candidate.sourceControlId().isBlank()) {
            return "Clause " + candidate.sourceControlId();
        }
        return "Untitled section";
    }

    /**
     * A short quotation identifying the skipped text.
     *
     * <p>Bounded because this is persisted and rendered. Standard text IS confidential (§35), but the
     * author reading it owns the document — and without the words they cannot find what was dropped.
     */
    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "(no text)";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= 90 ? single : single.substring(0, 90) + "…";
    }

    private boolean evaluatable(CandidateCategory category) {
        return category == CandidateCategory.MANDATORY_REQUIREMENT
                || category == CandidateCategory.PROHIBITION
                || category == CandidateCategory.CONDITIONAL_REQUIREMENT;
    }

    /**
     * One extraction attempt, retried at most once on validation failure (§12.8).
     *
     * <p>Exactly one retry, deliberately. A model that produced invalid output twice with the errors
     * in front of it is not going to succeed on a third attempt, and an unbounded loop turns a
     * malformed response into unbounded spend.
     */
    private CandidateOutcome callWithOneRetry(RequirementCandidate candidate,
                                             Map<String, EvidenceSpan> spansById,
                                             String standardId, String standardVersionId,
                                             String requirementSetVersion, String standardTitle,
                                             Set<String> seenRequirementIds) {
        var context = new RequirementExtractionModelGateway.Context(
                standardId, standardVersionId, requirementSetVersion, standardTitle);
        Set<String> allowed = new HashSet<>(candidate.spanIds());

        for (int attempt = 1; attempt <= 2; attempt++) {
            var result = gateway.extract(candidate, candidate.spanIds(), context);

            if (!result.rawResponseValid()) {
                if (attempt == 2) {
                    return new CandidateOutcome(List.of(), result.validationErrors(),
                            result.modelAlias(), result.promptTokens(), result.completionTokens());
                }
                continue;
            }

            // IDs are made unique BEFORE validation (§12.7). The model derives every requirement in
            // a candidate group from the same source control number, so a section with three
            // obligations returns three requirements all called "5.1" — and §12.8 rule 5 would reject
            // two of them, sending the whole set to NEEDS_REVIEW for what is a naming collision
            // rather than a bad extraction. Resolving it here keeps rule 5 as a genuine guard against
            // real duplicates while no longer discarding valid requirements.
            var identified = idAssigner.assign(result.requirements(), candidate, seenRequirementIds);
            var outcome = validator.validate(identified, allowed, seenRequirementIds);
            if (outcome.clean() || attempt == 2) {
                return new CandidateOutcome(outcome.accepted(), outcome.errors(),
                        result.modelAlias(), result.promptTokens(), result.completionTokens());
            }
            log.info("Retrying candidate {} after {} validation error(s)",
                    candidate.candidateId(), outcome.errors().size());
        }
        return new CandidateOutcome(List.of(), List.of("extraction exhausted retries"),
                null, 0, 0);
    }

    /**
     * Rule 10: consolidate duplicates WITHOUT losing source evidence.
     *
     * <p>Two candidates can legitimately produce the same requirement — a control restated in a
     * summary table, for instance. Discarding the second copy would lose its spans, so a reviewer
     * would see one citation where the standard states the obligation twice. The spans are merged
     * instead.
     */
    private List<ExtractedRequirement> consolidate(List<ExtractedRequirement> requirements) {
        Map<String, ExtractedRequirement> byKey = new LinkedHashMap<>();

        for (ExtractedRequirement r : requirements) {
            // Keyed on the normalized statement, not the requirement ID: two candidates produce
            // different IDs for the same obligation, which is exactly the case to merge.
            String key = r.normalizedRequirement() == null
                    ? r.requirementId()
                    : r.normalizedRequirement().toLowerCase().replaceAll("\\s+", " ").trim();

            var existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, r);
                continue;
            }

            var mergedSpans = new ArrayList<>(existing.sourceSpanIds());
            r.sourceSpanIds().stream()
                    .filter(id -> !mergedSpans.contains(id))
                    .forEach(mergedSpans::add);

            byKey.put(key, new ExtractedRequirement(
                    existing.requirementId(), existing.parentControlId(), existing.sourceControlId(),
                    existing.title(), List.copyOf(mergedSpans), existing.normativeStrength(),
                    existing.normalizedRequirement(), existing.atomicClauses(),
                    existing.applicability(), existing.expectedEvidenceHints(),
                    // Keep the higher confidence: one candidate may have had better context.
                    Math.max(existing.extractionConfidence(), r.extractionConfidence())));

            log.debug("Consolidated duplicate requirement into {} ({} spans)",
                    existing.requirementId(), mergedSpans.size());
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Next sequential set version for this standard version (§12.9).
     *
     * <p>Sequential rather than a timestamp or hash so an administrator can see at a glance that
     * REQSET-V3 supersedes V2, and so an old comparison's recorded version is meaningful on sight.
     */
    private String nextSetVersion(String standardVersionId) {
        int next = setVersionRepository.findByStandardVersionIdOrderByCreatedAtDesc(standardVersionId)
                .size() + 1;
        return "REQSET-V" + next;
    }

    private record CandidateOutcome(
            List<ExtractedRequirement> accepted,
            List<String> errors,
            String modelAlias,
            int promptTokens,
            int completionTokens
    ) {}
}
