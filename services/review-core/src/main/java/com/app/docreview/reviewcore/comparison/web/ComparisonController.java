package com.app.docreview.reviewcore.comparison.web;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.page.PageResponse;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos;
import com.app.docreview.reviewcore.comparison.application.ComparisonService;
import com.app.docreview.reviewcore.comparison.application.EvidenceHydrationService;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.ComparisonDetailResponse;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.ComparisonReference;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.ComparisonSummary;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.CreateComparisonRequest;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.DocumentSummary;
import com.app.docreview.reviewcore.comparison.web.dto.ComparisonDtos.FindingEvidenceResponse;
import com.app.docreview.reviewcore.security.CurrentUser;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The review workspace API. Architecture §8.1, §19.1.
 *
 * <p>Paths match what the React workspace already calls:
 *
 * <pre>
 *   GET /api/comparisons/{id}                            ComparisonWorkspace.tsx
 *   GET /api/comparisons/{id}/findings/{fid}/evidence     ComparisonWorkspace.tsx
 * </pre>
 *
 * <p>Creation returns 202, not 201: the comparison record exists but evaluation runs asynchronously
 * (§21). The UI polls the detail endpoint for progress, which is why {@code status},
 * {@code evaluatedRequirements}, and {@code totalRequirements} are on the detail response.
 *
 * <p>Findings are grouped BY STANDARD in the response rather than returned as one flat list, because
 * §19.1's side-by-side viewer opens one standard against the document at a time.
 */
@RestController
@RequestMapping("/api/comparisons")
@PreAuthorize("hasRole('REVIEWER')")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ComparisonController {

    private final ComparisonService comparisonService;
    private final EvidenceHydrationService hydrationService;
    private final StandardRepository standards;
    private final CurrentUser currentUser;

    @PostMapping
    public ResponseEntity<ComparisonDetailResponse> create(
            @Valid @RequestBody CreateComparisonRequest request) {

        var comparison = comparisonService.create(
                request.documentVersionId(), request.standardVersionIds(),
                request.comparisonGuidance(), currentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDetail(comparison));
    }

    /**
     * Runs the same review again, as a new comparison. Architecture §19.2.
     *
     * <p>POST, and 202: it creates a resource and queues work. The response is the NEW comparison, so
     * the client can navigate straight to it rather than guessing its id.
     *
     * <p>The original is untouched — §19.2 makes a completed review immutable, and a failed one is
     * still the record of what was attempted. Nothing about this endpoint edits it.
     */
    @PostMapping("/{publicId}/rerun")
    public ResponseEntity<ComparisonDetailResponse> rerun(@PathVariable String publicId) {
        var rerun = comparisonService.rerun(publicId, currentUser.id(), visibilityScope());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDetail(rerun));
    }

    @GetMapping("/{publicId}")
    public ComparisonDetailResponse get(@PathVariable String publicId) {
        return toDetail(comparisonService.getVisibleTo(publicId, visibilityScope()));
    }

    @GetMapping
    public List<ComparisonSummary> recent() {
        return comparisonService.recent(visibilityScope()).stream().map(this::toSummary).toList();
    }

    /**
     * One page of history, filtered and sorted server-side. Architecture §19.2.
     *
     * <p>Replaces filtering a capped list in the browser. {@code findTop50} silently hid the 51st
     * review, so a search for an older comparison returned nothing and looked identical to "it does
     * not exist".
     *
     * <p><strong>Two filters cannot be pushed to the database.</strong> MISMATCHES/CLEAN depend on
     * the finding count and MOST_FINDINGS sorts by it, and findings are an embedded array rather
     * than a stored scalar. Both are applied to the fetched page, and {@code exactCount} says so —
     * the caller must not present a filtered total as authoritative when it is per-page.
     */
    @GetMapping("/search")
    @PreAuthorize("hasRole('REVIEWER')")
    public PageResponse<ComparisonSummary> search(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "") String reference,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Bounded: an unbounded size lets one request pull the whole collection and defeats the
        // point of paginating at all.
        int bounded = Math.min(Math.max(size, 1), 100);
        var found = comparisonService.search(
                search, status, reference, days, sort, page, bounded, visibilityScope());

        var summaries = new ArrayList<>(found.getContent().stream().map(this::toSummary).toList());

        // Finding-count filters, applied after the fetch for the reason given above.
        boolean countFiltered = "MISMATCHES".equals(status) || "CLEAN".equals(status);
        if ("MISMATCHES".equals(status)) {
            summaries.removeIf(row -> row.actionableFindingCount() == 0);
        } else if ("CLEAN".equals(status)) {
            summaries.removeIf(row -> row.actionableFindingCount() > 0);
        }
        if ("MOST_FINDINGS".equals(sort)) {
            summaries.sort((a, b) ->
                    Integer.compare(b.actionableFindingCount(), a.actionableFindingCount()));
        }

        return new PageResponse<>(
                summaries,
                found.getTotalElements(),
                found.getNumber(),
                found.getSize(),
                found.getTotalPages(),
                // False when a post-fetch filter ran: the total then counts rows this page dropped,
                // and showing it as exact would misreport how many reviews actually match.
                !countFiltered && !"MOST_FINDINGS".equals(sort));
    }

    /**
     * Side-by-side evidence for one finding. Architecture §16.5, §19.1.
     *
     * <p>Every quotation returned here is loaded from a stored span, never from model output — see
     * {@link EvidenceHydrationService}.
     */
    @GetMapping("/{publicId}/findings/{findingId}/evidence")
    public FindingEvidenceResponse evidence(@PathVariable String publicId,
                                            @PathVariable String findingId) {
        // Ownership checked BEFORE the finding is loaded: evidence is the document's own text, so
        // reaching it by URL would expose exactly what the scope exists to protect.
        var comparison = comparisonService.getVisibleTo(publicId, visibilityScope());
        var finding = comparisonService.finding(publicId, findingId);
        return hydrationService.hydrate(finding, comparison.getDocumentVersionId());
    }

    private ComparisonDetailResponse toDetail(Comparison comparison) {
        var document = comparisonService.document(comparison.getDocumentVersionId());

        // Findings grouped by the standard version they came from (§19.1).
        Map<String, List<Finding>> byStandard = comparison.getFindings().stream()
                .collect(Collectors.groupingBy(Finding::standardVersionId));

        // Every standard version that was evaluated, whether or not it produced findings. A standard
        // with zero findings is a meaningful result — it means the document satisfied it — and
        // omitting it would make a clean review indistinguishable from one that never ran.
        var versionIds = new ArrayList<>(comparison.getStandardVersionIds());
        byStandard.keySet().stream().filter(id -> !versionIds.contains(id)).forEach(versionIds::add);

        var versions = comparisonService.standardVersions(versionIds).stream()
                .collect(Collectors.toMap(StandardVersion::getId, Function.identity(), (a, b) -> a));

        List<ComparisonReference> references = new ArrayList<>();
        for (String versionId : versionIds) {
            var version = versions.get(versionId);
            var standard = version == null ? null
                    : standards.findById(version.getStandardId()).orElse(null);

            references.add(new ComparisonReference(
                    version == null ? null : version.getStandardId(),
                    versionId,
                    standard == null ? null : standard.getStandardKey(),
                    standard == null ? null : standard.getTitle(),
                    version == null ? null : version.getVersion(),
                    // Retrieval score is populated by automatic selection (§15.1). For an explicit
                    // selection there is no score: the user chose, so a fabricated relevance number
                    // would imply a ranking that never happened.
                    0.0,
                    // Advertised whenever the version exists. The preview endpoint decides whether
                    // it can actually serve bytes (rendered preview, or a PDF source it can stream
                    // directly) and 404s otherwise. Gating on renderedObjectKey here suppressed the
                    // URL for every PDF standard — which are already renderable — and left the
                    // viewer pane empty.
                    version == null ? null : "/api/standards/" + versionId + "/preview",
                    null,
                    byStandard.getOrDefault(versionId, List.of())));
        }

        return new ComparisonDetailResponse(
                comparison.getId(),
                comparison.getPublicId(),
                comparison.getState().name(),
                new DocumentSummary(
                        document.getDocumentVersionId(),
                        document.getFilename(),
                        document.getPageCount(),
                        // Always advertised; the endpoint 404s if it has nothing to serve. See the
                        // standard renderUrl above for why this is not gated on renderedObjectKey.
                        "/api/uploads/" + document.getDocumentVersionId() + "/preview"),
                references,
                comparison.getComparisonModelAlias(),
                comparison.getComparisonPromptVersion(),
                comparison.getRequirementSetVersion(),
                comparison.getRetrievalStrategyVersion(),
                comparison.getEvaluatedRequirements(),
                comparison.getTotalRequirements(),
                comparison.getStateMessage(),
                // Empty rather than null for comparisons stored before this field existed, so the UI
                // does not need a null check to decide whether anything failed.
                comparison.getUnevaluatedRequirements() == null
                        ? List.of() : comparison.getUnevaluatedRequirements(),
                // The focus the review ran under (§19.2). Stored since the comparison was created but
                // never surfaced, so a reader could not tell a focused review from an unfocused one.
                comparison.getComparisonGuidance(),
                toCoverageSummary(comparison.getCoverageLedger()),
                // Exempt means "created before coverage tracking", which is the only legitimate reason
                // for a null summary. Anything else with no summary is a defect, and the UI needs to
                // be able to say so rather than reassure.
                comparison.getCoverageSchemaVersion() == null,
                comparison.getCreatedAt(),
                comparison.getCompletedAt());
    }

    /**
     * Coverage counts and the entries a reviewer must act on. Architecture §17.6.
     *
     * <p>Returns null for a comparison with no stored ledger, so the UI can say "coverage not
     * recorded". Substituting an empty summary would render zeros as full coverage — the one
     * misreading this feature exists to prevent.
     */
    private ComparisonDtos.CoverageSummary toCoverageSummary(CoverageLedger ledger) {
        if (ledger == null) {
            return null;
        }
        // Only the entries needing attention. A plainly evaluated requirement is already visible as a
        // finding, so sending all of them would mean 400 rows to render a four-line summary.
        var uncovered = ledger.entries().stream()
                .filter(e -> e.state() != CoverageLedger.CoverageState.EVALUATED)
                .map(e -> new ComparisonDtos.CoverageEntry(
                        e.requirementId(),
                        e.requirementTitle(),
                        e.standardVersionId(),
                        e.state().name(),
                        e.detail()))
                .toList();

        long compared = ledger.evaluatedCount();
        long reviewRequired = ledger.uncertainApplicabilityCount();
        // "Not evaluated" is the reviewer-facing name for entries that produced no verdict at all —
        // PENDING and FAILED. Deliberately NOT untestedCount(), which also counts the not-applicable
        // and review-required entries reported on their own lines.
        long notEvaluated = ledger.entries().stream()
                .filter(e -> e.state() == CoverageLedger.CoverageState.PENDING
                        || e.state() == CoverageLedger.CoverageState.FAILED)
                .count();

        var summary = new ComparisonDtos.CoverageSummary(
                ledger.expectedCount(),
                compared,
                // Confirmed is compared MINUS review-required, derived rather than counted
                // separately: two independent counts of overlapping sets is how a summary starts
                // failing to add up.
                compared - reviewRequired,
                reviewRequired,
                ledger.notApplicableCount(),
                notEvaluated,
                ledger.isComplete(),
                uncovered);

        // Asserted, not assumed. A summary whose parts do not account for every requirement is a
        // coverage claim with a hole in it, and no single number reveals the hole. Logged rather than
        // thrown: a reviewer is better served by a slightly-wrong summary plus a loud warning than by
        // a 500 that hides the findings entirely.
        if (!summary.countsReconcile()) {
            log.error("Coverage summary for {} does not reconcile: total={} compared={} "
                            + "(confirmed={} reviewRequired={}) notApplicable={} notEvaluated={}",
                    ledger.comparisonId(), summary.totalRequirements(), summary.compared(),
                    summary.applicabilityConfirmed(), summary.applicabilityReviewRequired(),
                    summary.notApplicable(), summary.notEvaluated());
        }
        return summary;
    }

    /**
     * Standards that appear in at least one comparison, for the history filter.
     *
     * <p>Derived from actual history rather than from the standards library: offering a standard
     * nothing was ever compared against produces a filter that always returns nothing, which reads
     * as a broken list. Derived from the whole collection rather than the current page, or filtering
     * to a standard and paging away would empty the dropdown.
     */
    @GetMapping("/standards")
    @PreAuthorize("hasRole('REVIEWER')")
    public List<StandardOption> usedStandards() {
        var seen = new java.util.LinkedHashMap<String, StandardOption>();
        // Scoped too: the dropdown is derived from the caller's own comparisons, so it cannot reveal
        // which standards other people have been reviewing against.
        for (var comparison : comparisonService.recent(visibilityScope())) {
            var ids = comparison.getStandardVersionIds();
            if (ids == null) {
                continue;
            }
            for (String versionId : ids) {
                if (seen.containsKey(versionId)) {
                    continue;
                }
                comparisonService.standardVersions(List.of(versionId)).stream().findFirst()
                        .flatMap(version -> standards.findById(version.getStandardId()))
                        .ifPresent(standard ->
                                seen.put(versionId, new StandardOption(versionId, standard.getTitle())));
            }
        }
        return seen.values().stream()
                .sorted(java.util.Comparator.comparing(StandardOption::title,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public record StandardOption(String standardVersionId, String title) {
    }

    private ComparisonSummary toSummary(Comparison comparison) {
        var document = comparisonService.document(comparison.getDocumentVersionId());
        var findings = comparison.getFindings();

        return new ComparisonSummary(
                comparison.getPublicId(),
                comparison.getDocumentVersionId(),
                document.getFilename(),
                comparison.getState().name(),
                findings.size(),
                // Aligned controls are retained (§18.6) but are not work items; the distinction is
                // what makes a history list scannable.
                (int) findings.stream().filter(Finding::actionable).count(),
                topStandardTitle(comparison),
                topStandardKey(comparison),
                comparison.getCreatedBy(),
                comparison.getCreatedAt(),
                comparison.getCompletedAt());
    }

    /**
     * The standard a history row is identified by.
     *
     * <p>The FIRST selected version, which is the highest-ranked one under automatic selection
     * (§15.1). A comparison can span several standards, but a list row needs one identifying line,
     * and the top reference is what a reviewer recognises the review by.
     *
     * <p>Null rather than a placeholder when it cannot be resolved: a comparison that failed before
     * any standard resolved genuinely has no reference, and inventing one would put a standard's name
     * against a review that never measured against it.
     */
    private String topStandardTitle(Comparison comparison) {
        var standard = topStandard(comparison);
        return standard == null ? null : standard.getTitle();
    }

    private String topStandardKey(Comparison comparison) {
        var standard = topStandard(comparison);
        return standard == null ? null : standard.getStandardKey();
    }

    private com.app.docreview.reviewcore.standards.domain.Standard topStandard(Comparison comparison) {
        var versionIds = comparison.getStandardVersionIds();
        if (versionIds == null || versionIds.isEmpty()) {
            return null;
        }
        return comparisonService.standardVersions(List.of(versionIds.getFirst())).stream()
                .findFirst()
                .flatMap(version -> standards.findById(version.getStandardId()))
                .orElse(null);
    }

    /**
     * The identity to scope reads by, or null for an unrestricted view. Architecture §33.
     *
     * <p>ADMIN only. STANDARDS_ADMIN manages the standards library, which does not imply reading
     * other people's reviews — those are two different authorities and conflating them would widen
     * this boundary silently the next time someone is given the standards role.
     */
    private String visibilityScope() {
        return currentUser.hasRole(com.app.docreview.reviewcore.security.Role.ADMIN)
                ? null
                : currentUser.id();
    }
}
