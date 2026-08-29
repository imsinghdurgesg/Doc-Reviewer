package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.comparison.ApplicabilityDecision;
import com.app.docreview.contracts.comparison.AtomicClauseResult;
import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.comparison.ComparisonModelRequest;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.Severity;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.contracts.retrieval.EvidenceCandidate;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import com.app.docreview.retrievalcomparison.comparison.aggregation.ClauseAggregator;
import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import com.app.docreview.retrievalcomparison.comparison.validation.ComparisonOutputValidator;
import com.app.docreview.retrievalcomparison.llm.ComparisonModelGateway;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares one requirement at a time and builds findings. Architecture §16, §18.
 *
 * <p><strong>Per-requirement isolation is the design.</strong> §18.4: "a failed requirement or
 * standard must not discard successful results from other requirements or standards". A comparison
 * over 200 requirements where three fail is a PARTIALLY_COMPLETED comparison with 197 valid findings,
 * not a failure — and not a success that quietly omits three.
 *
 * <p>Evidence is HYDRATED from stored spans (§16.5), never taken from the model response. The model
 * returns IDs; the text and coordinates a reviewer sees come from storage. That is what makes a
 * displayed quotation verifiable rather than asserted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequirementComparisonService {

    private final ComparisonModelGateway gateway;
    private final ComparisonOutputValidator validator;
    private final ClauseAggregator aggregator;
    private final EvidenceSpanReader spanReader;

    /**
     * @param severity      resolved deterministically upstream (§17.7) and passed in, so the model has
     *                      no route to influencing it
     * @param applicability the decision already made by {@link RequirementApplicabilityCoordinator},
     *                      passed in rather than re-resolved here.
     *                      <p><strong>Why passed in.</strong> Resolving it again locally produced two
     *                      independent answers for one requirement: the coordinator's governed decision
     *                      drove the skip gate and the coverage ledger, while a second extraction-only
     *                      resolution here drove what the reviewer actually read on the finding. The
     *                      two could disagree, and the human-reviewed one was the one being discarded.
     */
    public Outcome compare(String comparisonId, String documentVersionId,
                           String standardVersionId, String requirementSetVersion,
                           ExtractedRequirement requirement, Severity severity,
                           List<EvidenceCandidate> evidenceCandidates,
                           SearchCoverage coverage, ApplicabilityDecision applicability) {

        var request = buildRequest(comparisonId, documentVersionId, standardVersionId,
                requirementSetVersion, requirement, evidenceCandidates, coverage);

        Set<String> allowedStandardSpans = new HashSet<>(requirement.sourceSpanIds());
        Set<String> allowedProcessSpans = new HashSet<>();
        evidenceCandidates.forEach(c -> allowedProcessSpans.addAll(c.spanIds()));
        Set<String> declaredClauseIds = requirement.atomicClauses().stream()
                .map(AtomicClause::clauseId).collect(java.util.stream.Collectors.toSet());

        // §18.4: at most one retry. A model that produced invalid output twice with the errors in
        // front of it will not succeed on a third attempt, and an unbounded loop converts one bad
        // response into unbounded spend.
        // Carries the targeted correction into attempt 2 when attempt 1 failed in the one shape a
        // generic re-ask cannot fix (§18.4). Null for every other failure, which keeps the ordinary
        // retry unchanged.
        String repair = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            // The plain single-argument call when there is nothing to repair, which is always the
            // first attempt. Keeps the ordinary path on the ordinary method rather than routing every
            // comparison through the repair overload with a null.
            var result = repair == null
                    ? gateway.compare(request)
                    : gateway.compare(request, repair);

            if (!result.rawResponseValid()) {
                if (attempt == 2) {
                    return failed(requirement, result.errors());
                }
                continue;
            }

            var problems = validator.validate(result.output(), requirement.requirementId(),
                    allowedStandardSpans, allowedProcessSpans,
                    new HashSet<>(requirement.sourceSpanIds()), coverage, declaredClauseIds);

            if (problems.isEmpty()) {
                return new Outcome(
                        buildFinding(comparisonId, standardVersionId, requirementSetVersion,
                                requirement, severity, result.output(), coverage, evidenceCandidates,
                                applicability),
                        List.of(), result.promptTokens(), result.completionTokens());
            }

            if (attempt == 2) {
                log.warn("Comparison of {} failed validation twice: {}",
                        requirement.requirementId(), problems);
                return failed(requirement, problems);
            }
            // Failures whose cause is knowable from the response shape get a SPECIFIC correction. A
            // generic re-ask of a deterministic validation failure returns the same answer, which is
            // how one run turned 145 requirements into unevaluated controls.
            repair = repairFor(result.output(), problems);

            log.info("Retrying comparison of {} after {} validation problem(s){}",
                    requirement.requirementId(), problems.size(),
                    repair == null ? "" : " with a targeted repair instruction");
        }
        return failed(requirement, List.of("comparison exhausted retries"));
    }

    /**
     * The targeted correction for a failure whose cause is knowable, or null. Architecture §18.4.
     *
     * <p>Matched on the OUTPUT SHAPE rather than the problem string wherever possible, so rewording a
     * validator message cannot silently disable a targeted retry and send the case back to burning two
     * model calls for nothing.
     *
     * <p><strong>Why every extraction-limitation failure needs one.</strong> These are DETERMINISTIC:
     * the model produced a structurally invalid response, and re-asking the identical question produces
     * it again. In one run 123 of 145 failures were a limitation set with an empty obligation list, all
     * given the generic retry, all failing twice — 246 model calls that recorded nothing.
     */
    private static String repairFor(
            com.app.docreview.contracts.comparison.ComparisonModelOutput output,
            List<String> problems) {
        if (output == null) {
            return null;
        }

        // PARTIAL with nothing named as unsatisfied. The only shape whose cause is knowable from the
        // response itself, so the only one that gets a targeted correction; everything else takes the
        // ordinary re-ask, which is right for a malformed or transient response.
        if (output.status() == ComparisonStatus.PARTIAL
                && (output.unsatisfiedClauseIds() == null
                    || output.unsatisfiedClauseIds().isEmpty())
                && problems.stream().anyMatch(p -> p.contains("unsatisfied clause"))) {
            return ComparisonPrompt.INCOMPLETE_PARTIAL_REPAIR;
        }
        return null;
    }

    private ComparisonModelRequest buildRequest(String comparisonId, String documentVersionId,
                                                String standardVersionId,
                                                String requirementSetVersion,
                                                ExtractedRequirement requirement,
                                                List<EvidenceCandidate> candidates,
                                                SearchCoverage coverage) {

        // Standard evidence is loaded from storage, not carried in from extraction: the requirement
        // must be presented as the standard actually words it, and a hash-checked span is the only
        // form of that which cannot have drifted.
        var standardSpans = spanReader.byIds(standardVersionId, requirement.sourceSpanIds());
        var standardEvidence = standardSpans.stream()
                .map(s -> new ComparisonModelRequest.EvidencePayload(
                        s.spanId(), sectionOf(s), s.page(), s.exactText()))
                .toList();

        List<String> processSpanIds = candidates.stream()
                .flatMap(c -> c.spanIds().stream()).distinct().toList();
        var processSpans = spanReader.byIds(documentVersionId, processSpanIds);
        var processEvidence = processSpans.stream()
                .map(s -> new ComparisonModelRequest.EvidencePayload(
                        s.spanId(), sectionOf(s), s.page(), s.exactText()))
                .toList();

        return new ComparisonModelRequest(
                comparisonId, documentVersionId, standardVersionId, requirementSetVersion,
                new ComparisonModelRequest.RequirementPayload(
                        requirement.requirementId(), requirement.title(),
                        requirement.normalizedRequirement(), requirement.atomicClauses(),
                        requirement.sourceSpanIds()),
                standardEvidence, processEvidence, coverage);
    }

    private Finding buildFinding(String comparisonId, String standardVersionId,
                                 String requirementSetVersion, ExtractedRequirement requirement,
                                 Severity severity, ComparisonModelOutput output,
                                 SearchCoverage coverage, List<EvidenceCandidate> candidates,
                                 ApplicabilityDecision applicability) {

        var clauseResults = buildClauseResults(requirement, output);

        // The parent status is COMPUTED from the clauses (§18.5), not taken from the response. The
        // model's own overall status is discarded here on purpose: a deterministic roll-up cannot
        // contradict its own clause verdicts, and a reviewer can reproduce it.
        var aggregated = clauseResults.isEmpty()
                ? output.status()
                : aggregator.aggregate(clauseResults, coverage);


        // For an absence verdict, name the closest passage that WAS considered (§18.6). A gap with no
        // evidence at all is unfalsifiable: the reviewer cannot tell whether the tool looked in the
        // right place.
        List<String> nearest = List.of();
        if ((aggregated == ComparisonStatus.MISSING
                || aggregated == ComparisonStatus.INSUFFICIENT_EVIDENCE)
                && !candidates.isEmpty()) {
            // Capped: the top-ranked CHUNK can span an entire short document (31 spans in a
            // two-page procedure), and highlighting all of it as "nearest related passage" fills the
            // viewer beneath a finding that says the requirement is absent. A handful of spans shows
            // where the tool looked, which is what §18.6 asks for.
            nearest = candidates.get(0).spanIds().stream().limit(MAX_NEAREST_SPANS).toList();
        }

        return new Finding(
                "FND-" + UUID.randomUUID().toString().substring(0, 12),
                comparisonId, standardVersionId, requirementSetVersion,
                requirement.requirementId(), requirement.parentControlId(), requirement.title(),
                aggregated,
                // gapType applies to PARTIAL only; dropped otherwise so it cannot mislead.
                aggregated == ComparisonStatus.PARTIAL ? output.gapType() : null,
                severity,
                output.standardSpanIds() == null ? List.of() : output.standardSpanIds(),
                // §17.3 / §18.6: an ABSENCE verdict has no SUPPORTING process evidence. A model asked
                // "is this requirement met?" routinely answers MISSING while listing the passages it
                // read, and passing those through as processSpanIds makes them look like proof — the
                // viewer highlighted almost the whole document beneath a "missing" finding, which
                // reads as "here is where it is" for a requirement that is not there at all.
                //
                // They are not discarded: §18.6 keeps them as nearestProcessSpanIds, which the UI
                // presents as context ("this is what was considered") rather than as evidence.
                supportingProcessSpans(aggregated, output),
                nearestProcessSpans(aggregated, output, nearest),
                clauseResults,
                output.reason(),
                output.recommendedAction(),
                output.confidence(),
                // Escalate when the model asks for review OR when the deterministic roll-up
                // disagreed with the model's own status — a disagreement is exactly the case a human
                // should look at.
                output.needsHumanReview() || aggregated != output.status()
                        || applicability.status().needsScopeReview(),
                // Applicability travels ALONGSIDE the comparison status (§12.5). Resolved from the
                // requirement's own stated condition and the document type — no extra model call, and
                // no call at all for the unconditional majority.
                applicability.status(),
                applicability.reason(),
                applicability.decidedBy(),
                // Null: this is the ORIGINAL run. A retry stamps its own provenance (§19.2).
                null,
                // No evaluation limitation from the comparison path. A finding produced here was
                // compared against a requirement whose integrity was already validated at ingestion, so
                // the limitation cannot arise: it is set only by the deterministic integrity path,
                // which never invokes the model. Letting the model set it is what made V3 misfire.
                null, List.of(), true);
    }

    /**
     * Process spans that SUPPORT the verdict.
     *
     * <p>Empty for MISSING: §17.3 states an absence verdict may have no supporting process span, and
     * §18.3 requires supporting evidence only for ALIGNED, PARTIAL, and CONTRADICTED. A model answering
     * MISSING commonly still lists everything it read; treating that as support asserts the
     * requirement is satisfied somewhere while the verdict says it is not.
     */
    private static List<String> supportingProcessSpans(ComparisonStatus status,
                                                       ComparisonModelOutput output) {
        if (status == ComparisonStatus.MISSING) {
            return List.of();
        }
        if (output.processSpanIds() == null) {
            return List.of();
        }
        // Bounded. A model asked about ONE requirement routinely cites every passage it was shown —
        // for a retention-period requirement it returned the document title, the purpose paragraph,
        // the scope line AND the relevant clause. Highlighting all of them makes the pane look like
        // the whole document is evidence, and the reviewer cannot see WHICH sentence the verdict
        // rests on.
        //
        // The cited spans arrive in the model's own order, which puts the passage it reasoned from
        // first, so truncation keeps the most relevant ones. §16.5 still hydrates each from storage,
        // so nothing shown is unverified.
        return output.processSpanIds().stream().limit(MAX_SUPPORTING_SPANS).toList();
    }

    /**
     * Cap on supporting process spans per finding.
     *
     * <p>Four allows a requirement genuinely satisfied across a couple of sentences to cite them all,
     * while stopping a whole-document highlight.
     */
    private static final int MAX_SUPPORTING_SPANS = 4;

    /**
     * Passages that were CONSIDERED but did not satisfy the requirement (§18.6).
     *
     * <p>For MISSING, the spans the model cited move here — that is exactly what §18.6 means by
     * "store the closest related span IDs separately as context, not as proof". It is also what makes
     * a MISSING finding falsifiable: a reviewer can see where the tool looked.
     *
     * <p>Bounded, because a model can echo every retrieved passage. Highlighting a whole document as
     * "nearest" tells the reviewer nothing and buries the actual verdict.
     */
    private static List<String> nearestProcessSpans(ComparisonStatus status,
                                                    ComparisonModelOutput output,
                                                    List<String> retrievedNearest) {
        if (status != ComparisonStatus.MISSING) {
            return retrievedNearest;
        }
        var cited = output.processSpanIds();
        if (cited == null || cited.isEmpty()) {
            return retrievedNearest;
        }
        return cited.stream().limit(MAX_NEAREST_SPANS).toList();
    }

    /**
     * Cap on "nearest related passage" spans.
     *
     * <p>Three is enough to show where the tool looked without turning the viewer into a fully
     * highlighted document, which is what 25 produced in practice.
     */
    private static final int MAX_NEAREST_SPANS = 3;

    /**
     * Derives per-clause results from the satisfied/unsatisfied lists.
     *
     * <p>A clause named in neither list was not assessed, and is recorded as INSUFFICIENT_EVIDENCE
     * rather than assumed satisfied. Assuming the favourable case is how a real gap disappears.
     */
    /**
     * Maps model-returned clause references onto declared clause IDs.
     *
     * <p>Mirrors {@code ComparisonOutputValidator.resolveClauseId}: an exact match first, then the
     * longest declared ID the reference starts with. Prefix rather than separator-splitting because
     * real clause IDs contain dots ({@code 3.3-a}), so the annotation separator cannot be told apart
     * from the ID's own punctuation.
     *
     * <p>Several references can collapse onto ONE clause — {@code who}/{@code does}/{@code to} all
     * resolving to {@code 4-2} — which is correct: they are one obligation the model described in
     * parts.
     */
    private Set<String> resolveAll(List<String> cited, Set<String> declared) {
        if (cited == null) {
            return Set.of();
        }
        Set<String> resolved = new HashSet<>();
        for (String reference : cited) {
            if (reference == null || reference.isBlank()) {
                continue;
            }
            String bare = reference.indexOf(':') >= 0
                    ? reference.substring(0, reference.indexOf(':')).strip()
                    : reference.strip();
            if (declared.contains(bare)) {
                resolved.add(bare);
                continue;
            }
            declared.stream()
                    .filter(d -> d != null && !d.isBlank() && bare.startsWith(d))
                    .max(java.util.Comparator.comparingInt(String::length))
                    .ifPresent(resolved::add);
        }
        return resolved;
    }

    private List<AtomicClauseResult> buildClauseResults(ExtractedRequirement requirement,
                                                        ComparisonModelOutput output) {
        // Resolved against the DECLARED ids, matching how the validator accepted them.
        //
        // A model may prefix a real clause id with its own annotation ("4-2.who" for declared
        // "4-2"). The validator tolerates that rather than failing the requirement; if this method
        // then compared exactly, every clause would fall through to INSUFFICIENT_EVIDENCE — a
        // silently unassessed requirement, which is worse than the validation error it replaced.
        var declared = requirement.atomicClauses().stream()
                .map(AtomicClause::clauseId)
                .collect(java.util.stream.Collectors.toSet());
        var satisfied = resolveAll(output.satisfiedClauseIds(), declared);
        var unsatisfied = resolveAll(output.unsatisfiedClauseIds(), declared);

        if (satisfied.isEmpty() && unsatisfied.isEmpty()) {
            // The model gave no clause breakdown; fall back to its overall status rather than
            // fabricating per-clause verdicts it never expressed.
            return List.of();
        }

        List<AtomicClauseResult> results = new ArrayList<>();
        for (AtomicClause clause : requirement.atomicClauses()) {
            ComparisonStatus status;
            if (satisfied.contains(clause.clauseId())) {
                status = ComparisonStatus.ALIGNED;
            } else if (unsatisfied.contains(clause.clauseId())) {
                // A contradicted parent means the unsatisfied clause is the conflict itself;
                // otherwise the element is simply absent.
                status = output.status() == ComparisonStatus.CONTRADICTED
                        ? ComparisonStatus.CONTRADICTED : ComparisonStatus.MISSING;
            } else {
                status = ComparisonStatus.INSUFFICIENT_EVIDENCE;
            }
            results.add(new AtomicClauseResult(clause.clauseId(),
                    // Rendered from the stored clause, so the row says "publish change freeze
                    // periods" rather than only "5.1-a". Derived, never model-generated.
                    clause.displayText(),
                    status,
                    status == ComparisonStatus.MISSING ? output.gapType() : null,
                    // Deliberately EMPTY, and deliberately NULL, under schema V1.
                    //
                    // The V1 model contract returns one reason and one span list for the whole
                    // requirement. Copying them onto each clause — which this did — displayed the
                    // same multi-clause paragraph on every clause row, and let an ALIGNED clause
                    // claim every span the requirement cited. Both assert an attribution the model
                    // never made, which is exactly what §16.5 exists to prevent.
                    //
                    // The parent finding still carries the reason and the evidence; the UI renders
                    // them once at requirement level. Genuine per-clause values need the model to
                    // produce them (schema V2), not this method to invent them.
                    List.of(),
                    null));
        }
        return results;
    }

    private Outcome failed(ExtractedRequirement requirement, List<String> errors) {
        // COMPARISON_FAILED is surfaced as INSUFFICIENT_EVIDENCE with the diagnostics preserved
        // (§18.4). Silently omitting the requirement would make the comparison look complete.
        return new Outcome(null, errors, 0, 0);
    }

    private String sectionOf(EvidenceSpan span) {
        return span.sectionPath() == null || span.sectionPath().isEmpty()
                ? null : String.join(" > ", span.sectionPath());
    }

    /**
     * §18.7: consolidates findings that describe the same gap.
     *
     * <p>Keyed on standard version, requirement set, requirement, and gap type. Multiple relevant
     * process locations become supporting spans under ONE finding rather than duplicate findings —
     * three findings for the same missing control reads as three problems.
     */
    public List<Finding> consolidate(List<Finding> findings) {
        Map<String, Finding> byKey = new LinkedHashMap<>();

        for (Finding f : findings) {
            String key = String.join("|", f.standardVersionId(), f.requirementSetVersion(),
                    f.requirementId(), String.valueOf(f.gapType()));
            var existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, f);
                continue;
            }

            var mergedProcess = new ArrayList<>(existing.processSpanIds());
            f.processSpanIds().stream()
                    .filter(id -> !mergedProcess.contains(id))
                    .forEach(mergedProcess::add);

            byKey.put(key, new Finding(existing.findingId(), existing.comparisonId(),
                    existing.standardVersionId(), existing.requirementSetVersion(),
                    existing.requirementId(), existing.parentControlId(),
                    existing.requirementTitle(), existing.status(), existing.gapType(),
                    existing.severity(), existing.standardSpanIds(), List.copyOf(mergedProcess),
                    existing.nearestProcessSpanIds(), existing.clauseResults(),
                    existing.reason(), existing.recommendedAction(),
                    Math.max(existing.confidence(), f.confidence()),
                    existing.needsHumanReview() || f.needsHumanReview(),
                    // Preserved from the existing finding: merging duplicate findings for one
                    // requirement must not change whether that requirement applied, or what decided it.
                    existing.applicabilityStatus(), existing.applicabilityReason(),
                    existing.applicabilityDecidedBy(),
                    // Preserved: merging duplicates must not erase that a finding was retried.
                    existing.retriedAs(),
                    // Preserved for the same reason: merging duplicate findings must not silently
                    // restore an extraction-gap result to the alignment conclusion.
                    existing.evaluationLimitation(),
                    existing.unmappedStandardObligations(),
                    existing.conclusionEligible()));
        }
        return List.copyOf(byKey.values());
    }

    /**
     * @param finding null when comparison failed after the permitted retry; {@code errors} then
     *                carries the diagnostics and the caller records PARTIALLY_COMPLETED
     */
    public record Outcome(Finding finding, List<String> errors,
                          int promptTokens, int completionTokens) {

        public boolean succeeded() {
            return finding != null;
        }
    }
}
