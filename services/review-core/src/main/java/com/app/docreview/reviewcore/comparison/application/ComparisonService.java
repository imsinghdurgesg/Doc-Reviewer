package com.app.docreview.reviewcore.comparison.application;

import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.job.ComparisonState;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.ComparisonFindingReader;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Comparison lifecycle. Architecture §8.1, §19.2, §20.2.
 *
 * <p>Creation enforces two preconditions that exist to prevent the same failure mode — a comparison
 * that returns nothing and reads as full compliance:
 *
 * <ol>
 *   <li><strong>The document must be processed.</strong> Without spans there is no evidence to
 *       retrieve, so every requirement would come back MISSING (§17.5 makes this an
 *       insufficient-evidence situation, not an absence one).</li>
 *   <li><strong>Every standard must be comparable.</strong> §8.1 requires ACTIVE and INDEXED. An
 *       unindexed standard has no extracted requirements, so it contributes no findings at all.</li>
 * </ol>
 *
 * <p>Both are rejected at creation with a specific message rather than allowed to produce an empty
 * result, because an empty result is indistinguishable from a clean review.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComparisonService {

    /**
     * The per-comparison requirement bound, read from Admin so a selection can be refused BEFORE a
     * run starts rather than truncated during one (§15.5).
     *
     * <p><strong>One stored value, read by both services.</strong> This was an environment variable
     * mirrored in retrieval-comparison, and two services holding one number is a real risk: if
     * review-core's was higher, a selection it accepted was silently truncated by the worker and the
     * dropped requirements were reported as never attempted. The worker now fetches this same row per
     * job, so they cannot disagree.
     */

    private final ComparisonRepository comparisons;
    /** Admin-configured limits; read per request so a change applies without a restart. */
    private final com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService reviewLimits;
    private final UploadedDocumentRepository documents;
    private final StandardVersionRepository standardVersions;
    private final JobDispatcher jobDispatcher;
    /**
     * Reads findings and coverage back from the worker.
     *
     * <p>Needed for a targeted retry: the amendment happens in the worker's working set, and this
     * service must re-pull both to keep the authoritative comparison consistent with it.
     */
    private final com.app.docreview.reviewcore.workflow.ComparisonFindingReader findingReader;

    /**
     * Creates a comparison and queues evaluation.
     *
     * @param standardVersionIds explicit selection; empty requests automatic selection (§15.1)
     */
    /**
     * @param guidance optional reviewer assessment context. Stored on the comparison and passed to
     *                 retrieval; blank is normalised to null so "not supplied" and "supplied empty"
     *                 do not become two states meaning the same thing.
     */
    public Comparison create(String documentVersionId, List<String> standardVersionIds,
                             String guidance, String actor) {
        var document = documents.findByDocumentVersionId(documentVersionId)
                .orElseThrow(() -> ApiException.notFound("Uploaded document"));

        if (!document.readyForComparison()) {
            throw ApiException.conflict("DOCUMENT_NOT_PROCESSED",
                    "Document " + documentVersionId + " is " + document.getState()
                            + " and has no evidence spans yet. Comparing now would report every "
                            + "requirement as missing.");
        }

        var selected = standardVersionIds == null ? List.<String>of() : standardVersionIds;
        requireComparableStandards(selected);

        var comparison = comparisons.save(new Comparison(
                "CMP-" + UUID.randomUUID().toString().substring(0, 12),
                documentVersionId, selected, actor));

        // Snapshot what we already know. The model, prompt, and index versions are filled in from
        // the worker callback — §19.2 requires the full set, and the worker is the only place that
        // knows which alias actually ran.
        comparison.setChunkingStrategyVersion(document.getChunkingStrategyVersion());
        comparison.setState(ComparisonState.SEARCHING_STANDARDS);

        // Recorded BEFORE dispatch, so the stored context cannot disagree with the run it steered.
        var trimmedGuidance = guidance == null || guidance.isBlank() ? null : guidance.trim();
        comparison.setComparisonGuidance(trimmedGuidance);
        comparisons.save(comparison);

        jobDispatcher.dispatchRetrievalComparison(
                comparison.getPublicId(), documentVersionId, selected, trimmedGuidance);

        log.info("Created comparison {} for document {} against {} standard(s)",
                comparison.getPublicId(), documentVersionId,
                selected.isEmpty() ? "automatically selected" : selected.size());
        return comparison;
    }

    /**
     * Runs the same review again as a NEW comparison. Architecture §19.2.
     *
     * <p><strong>A new record, never a mutation.</strong> §19.2 makes a completed comparison immutable,
     * so this creates a fresh one from the same document, standards and guidance. The original keeps
     * its findings, its state and its provenance — a reviewer can still see what the earlier attempt
     * produced and why it failed, which is the point of recording a failure rather than discarding it.
     *
     * <p>The rerun therefore uses CURRENT versions throughout: today's requirement sets, governance
     * overrides, prompts and models. It is a new assessment that happens to have the same inputs, not
     * a replay of the old one — and its own provenance records exactly that.
     *
     * <p>Reruns any state, not only FAILED. A COMPLETED review is worth repeating after a standard is
     * re-extracted or an override is confirmed, and refusing that would send the reviewer back to the
     * upload screen to reconstruct a selection they already made.
     */
    public Comparison rerun(String publicId, String actor, String ownerOrNull) {
        // SCOPED read (§33). `get` bypasses ownership, so rerunning through it would let a caller
        // clone a comparison they cannot even open — and the new record would name their own document.
        var original = getVisibleTo(publicId, ownerOrNull);

        // Re-validated, deliberately. A standard comparable when the original ran may since have been
        // archived, or its requirement count may now exceed the per-comparison limit — so this goes
        // through create() rather than dispatching directly, and fails with the same explicit reasons.
        var rerun = create(
                original.getDocumentVersionId(),
                original.getStandardVersionIds(),
                original.getComparisonGuidance(),
                actor);

        log.info("Comparison {} rerun as {} ({} standard(s))",
                publicId, rerun.getPublicId(),
                original.getStandardVersionIds() == null
                        ? 0 : original.getStandardVersionIds().size());
        return rerun;
    }

    /**
     * Re-evaluates ONE failed requirement and amends this comparison. Architecture §18.4, §19.2.
     *
     * <p>1 model call rather than the whole comparison. A single requirement is usually rejected because
     * the model's verdict contradicted the evidence it cited — a per-call inconsistency — so repeating
     * every other requirement to recover it is waste, and it produces a second comparison the reviewer
     * must re-read.
     *
     * <p><strong>This DOES amend a stored comparison</strong>, which §19.2 otherwise forbids. The
     * amendment is made auditable instead of prevented: the retried finding carries its own prompt,
     * model and override versions, so a reader can see that one row was produced separately and under
     * what. Without that stamp the comparison would claim a single provenance it no longer had.
     *
     * <p>Findings and coverage are both re-pulled afterwards. Updating one without the other would
     * leave a finding present while coverage still called the requirement untested.
     */
    public Comparison retryRequirement(String publicId, String requirementId, String ownerOrNull) {
        var comparison = getVisibleTo(publicId, ownerOrNull);

        // Refused BEFORE the model is invoked. An extraction gap is upstream of the comparison: the
        // clause the model needs was never produced, so every retry reaches the same wall. CHG-08-R2
        // spent eight model calls proving that. Rejecting it here turns a silent, repeatable waste
        // into a statement of what actually has to be fixed.
        var existing = comparison.getCoverageLedger();
        if (existing != null && existing.entries().stream()
                .anyMatch(e -> requirementId.equals(e.requirementId())
                        && !e.state().retryable())) {
            throw ApiException.conflict("RETRY_NOT_APPLICABLE",
                    "Requirement " + requirementId + " was compared, but the extracted standard "
                            + "requirement was incomplete. Retrying cannot supply the missing clause; "
                            + "the extraction must be corrected. This result is excluded from the "
                            + "conclusion and is flagged for review.");
        }

        if (!findingReader.retryRequirement(publicId, requirementId)) {
            throw ApiException.conflict("RETRY_FAILED",
                    "Requirement " + requirementId + " could not be evaluated on retry. It remains "
                            + "untested and is still excluded from the conclusion.");
        }

        comparison.setFindings(findingReader.read(publicId));
        var fetch = findingReader.readCoverage(publicId);
        if (fetch.outcome() == ComparisonFindingReader.CoverageFetch.Outcome.FOUND) {
            comparison.setCoverageLedger(fetch.ledger());
        }

        // THE HEADER COUNTERS, recomputed from the ledger.
        //
        // They are normally set from the callback's completedUnits/totalUnits, and a retry sends no
        // callback — so "186 / 187" stayed on screen after a successful retry while the coverage panel
        // below it showed the requirement recovered. Two numbers describing the same thing, disagreeing.
        //
        // The ledger is the authority here: it has one row per expected requirement and knows which
        // produced a verdict, which is exactly what these counters claim to report.
        var ledger = comparison.getCoverageLedger();
        if (ledger != null) {
            comparison.setEvaluatedRequirements((int) ledger.evaluatedCount());
            comparison.setTotalRequirements(ledger.expectedCount());

            // AND the named list behind the top warning, which is what a reviewer actually reads.
            // It came from the callback's unitErrors and was never revised, so a recovered requirement
            // stayed named as unevaluated — the most specific, most trusted statement on the page,
            // still asserting a gap that had just been closed.
            //
            // Rebuilt from the entries that genuinely produced no verdict, in the same
            // "requirementId: reason" form the UI parses.
            comparison.setUnevaluatedRequirements(ledger.entries().stream()
                    .filter(e -> !e.state().producedVerdict())
                    .filter(e -> e.state() != com.app.docreview.contracts.comparison.CoverageLedger
                            .CoverageState.SKIPPED)
                    .map(e -> e.requirementId() + ": "
                            + (e.detail() == null ? "not evaluated" : e.detail()))
                    .toList());
        }

        // THE STATE, derived from the ledger in BOTH directions.
        //
        // Not only "can improve to COMPLETED": a retry on a comparison with other gaps left the old
        // FAILED state and its now-wrong message in place, and a comparison recorded FAILED for a
        // missing coverage snapshot stayed FAILED after coverage was rebuilt. The state has to follow
        // the coverage record, not accumulate from whatever it was before.
        if (ledger != null) {
            // UNTESTED, not `isComplete()`. isComplete() asks whether anything is still PENDING — and
            // FAILED is terminal, so a comparison with a permanently failed requirement satisfies it.
            // The right question for the comparison's state is whether every requirement produced a
            // verdict, which is what a reviewer reads "completed" as meaning.
            long untested = ledger.entries().stream()
                    .filter(e -> !e.state().producedVerdict())
                    .filter(e -> e.state() != com.app.docreview.contracts.comparison.CoverageLedger
                            .CoverageState.SKIPPED)
                    .count();

            long extractionReview = ledger.extractionReviewRequiredCount();

            if (untested == 0) {
                // Every requirement produced a verdict, so the comparison IS complete — an extraction
                // gap is a result, not a gap in coverage.
                comparison.setState(ComparisonState.COMPLETED);
                // But COMPLETED with no message would imply the conclusion rests on all of them. It
                // does not: an extraction-gap result is excluded, because a stated obligation went
                // unassessed. Saying so here is the difference between a complete review and a review
                // that merely finished.
                comparison.setStateMessage(extractionReview == 0 ? null
                        : extractionReview + " of " + ledger.expectedCount()
                                + " requirements were compared against an incomplete extracted "
                                + "requirement and are excluded from the conclusion. They need "
                                + "extraction review, not re-evaluation.");
            } else {
                // Still gaps, so still partial — with a message that names the CURRENT shortfall
                // rather than the one from the original run.
                comparison.setState(ComparisonState.PARTIALLY_COMPLETED);
                comparison.setStateMessage(untested + " of " + ledger.expectedCount()
                        + " requirements could not be evaluated.");
            }
        }
        comparisons.save(comparison);

        log.info("Retried requirement {} in comparison {}; state now {}",
                requirementId, publicId, comparison.getState());
        return comparison;
    }

    public Comparison get(String publicId) {
        return comparisons.findByPublicId(publicId)
                .orElseThrow(() -> ApiException.notFound("Comparison"));
    }

    /**
     * A comparison the caller is allowed to read. Architecture §33.
     *
     * <p><strong>NOT FOUND, not forbidden.</strong> A 403 confirms the comparison exists, which leaks
     * that someone reviewed a particular document — and on a shared deployment the public id is
     * guessable enough to probe. 404 is the same answer whether the review is absent or someone
     * else's, so the response reveals nothing either way.
     *
     * <p>An unattributed comparison ({@code createdBy} null, from before attribution was recorded) is
     * readable only when unrestricted. Treating a missing owner as "anyone may read it" would make
     * legacy rows the one gap in this boundary.
     *
     * @param ownerOrNull the caller's identity, or null to bypass the check (administrators)
     */
    public Comparison getVisibleTo(String publicId, String ownerOrNull) {
        var comparison = get(publicId);
        if (ownerOrNull == null) {
            return comparison;
        }
        if (!ownerOrNull.equals(comparison.getCreatedBy())) {
            throw ApiException.notFound("Comparison");
        }
        return comparison;
    }

    /**
     * One page of history, filtered and sorted by the database. Architecture §19.2.
     *
     * <p>Server-side rather than in the browser. A client-side filter needs the whole collection
     * first, which means a capped fetch — and a cap silently hides older reviews, so a search for a
     * comparison from last quarter returns nothing with no indication that anything was omitted.
     *
     * @param search    matched against filename and public id; blank matches everything
     * @param status    a UI status group, or blank for all
     * @param reference a standard version id to restrict to, or blank for all
     * @param days      only reviews started within this many days, or null for all time
     */
    public org.springframework.data.domain.Page<Comparison> search(
            String search, String status, String reference, Integer days,
            String sort, int page, int size, String ownerOrNull) {

        boolean searching = search != null && !search.isBlank();
        // Quoted: a filename can legitimately contain regex metacharacters, and an unescaped "(" is
        // an invalid pattern that fails the whole query rather than matching nothing.
        String term = searching ? java.util.regex.Pattern.quote(search.trim()) : "";

        // Filename lives on the document, so matching ids are resolved first. Empty when not
        // searching, which the query then ignores entirely.
        List<String> documentIds = searching
                ? documents.findByFilenameContainingIgnoreCase(search.trim()).stream()
                        .map(UploadedDocument::getDocumentVersionId).toList()
                : List.of();

        var createdAfter = days == null
                ? java.time.Instant.EPOCH
                : java.time.Instant.now().minus(java.time.Duration.ofDays(days));

        var pageable = org.springframework.data.domain.PageRequest.of(page, size, sortFor(sort));

        var states = statesFor(status);
        return comparisons.search(term, documentIds, searching, states,
                reference == null ? "" : reference, createdAfter, !states.isEmpty(),
                // "" means unrestricted. The CALLER decides who may see everything — this method
                // takes a plain filter so authorization stays where the principal is (§33).
                ownerOrNull == null ? "" : ownerOrNull,
                pageable);
    }

    /**
     * Maps a UI status group to persisted states.
     *
     * <p>MISMATCHES and CLEAN are NOT states — they depend on the finding count — so they resolve to
     * the terminal states here and are narrowed afterwards. Treating them as states would silently
     * return nothing.
     */
    private List<String> statesFor(String status) {
        if (status == null || status.isBlank() || "ALL".equals(status)) {
            return List.of();
        }
        return switch (status) {
            case "FAILED" -> List.of("FAILED");
            case "RUNNING" -> List.of("PENDING", "RUNNING", "DISPATCHED", "RETRY_WAIT");
            case "MISMATCHES", "CLEAN" -> List.of("COMPLETED", "PARTIALLY_COMPLETED");
            default -> List.of();
        };
    }

    private org.springframework.data.domain.Sort sortFor(String sort) {
        return "OLDEST".equals(sort)
                ? org.springframework.data.domain.Sort.by("createdAt").ascending()
                : org.springframework.data.domain.Sort.by("createdAt").descending();
    }

    /**
     * Recent comparisons, scoped to one owner unless {@code ownerOrNull} is null.
     *
     * <p>Null is unrestricted and is reserved for administrators: this list previously showed every
     * reviewer the whole organization's activity.
     */
    public List<Comparison> recent(String ownerOrNull) {
        return ownerOrNull == null
                ? comparisons.findTop50ByOrderByCreatedAtDesc()
                : comparisons.findTop50ByCreatedByOrderByCreatedAtDesc(ownerOrNull);
    }

    /**
     * Finds one finding within a comparison.
     *
     * <p>Scoped to the comparison rather than looked up globally: a finding ID from another
     * comparison must not resolve here, or the viewer would show evidence from a document the user
     * did not open (§18.2).
     */
    public Finding finding(String publicId, String findingId) {
        return get(publicId).getFindings().stream()
                .filter(f -> findingId.equals(f.findingId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Finding"));
    }

    public UploadedDocument document(String documentVersionId) {
        return documents.findByDocumentVersionId(documentVersionId)
                .orElseThrow(() -> ApiException.notFound("Uploaded document"));
    }

    public List<StandardVersion> standardVersions(List<String> ids) {
        return standardVersions.findAllById(ids);
    }

    private void requireComparableStandards(List<String> standardVersionIds) {
        int totalRequirements = 0;

        for (String id : standardVersionIds) {
            var version = standardVersions.findById(id)
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_STANDARD_VERSION",
                            "Standard version " + id + " does not exist."));

            if (!version.comparable()) {
                throw ApiException.conflict("STANDARD_NOT_COMPARABLE",
                        "Standard version " + version.getVersion() + " is "
                                + version.getLifecycle() + "/" + version.getIndexingState()
                                + ". An unindexed standard has no requirements, so the comparison "
                                + "would produce no findings.");
            }
            totalRequirements += version.getMaterialRequirementCount();
        }

        // REFUSED UP FRONT, not truncated during the run.
        //
        // The worker bounds requirements per comparison (§15.5). Exceeding it used to truncate
        // silently: a three-standard selection of 187 requirements against a cap of 120 gave the first
        // standard all 84, the second 36 of 44, and the third NOTHING — and the reviewer got an empty
        // tab for a standard they had chosen, after paying for a full run.
        //
        // Rejecting here is the honest behaviour. A reviewer who selected too much should be told
        // before the comparison starts, with the numbers, rather than handed a partial result that
        // looks complete. The truncation path still exists as a backstop and now records dropped
        // requirements as untested coverage rows — but a reviewer should never reach it.
        int maxRequirementsPerComparison = reviewLimits.current().getMaxRequirementsPerComparison();
        if (totalRequirements > maxRequirementsPerComparison) {
            throw ApiException.badRequest("TOO_MANY_REQUIREMENTS",
                    "The selected standards contain " + totalRequirements
                            + " requirements, more than the " + maxRequirementsPerComparison
                            + " a single comparison evaluates. Select fewer standards, or compare "
                            + "against them in separate reviews so every requirement is assessed.");
        }
    }
}
