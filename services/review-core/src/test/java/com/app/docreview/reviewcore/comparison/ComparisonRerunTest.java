package com.app.docreview.reviewcore.comparison;

import com.app.docreview.contracts.job.ComparisonState;
import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.comparison.application.ComparisonService;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rerunning a review. Architecture §19.2, §33.
 *
 * <p><strong>Why this exists.</strong> A failed comparison told the reviewer to "rerun the comparison"
 * and gave them no way to. The only route was back to the upload screen to reconstruct the same
 * standard selection from memory — which is not a remedy for a review that already recorded exactly
 * which standards it used.
 *
 * <p><strong>A new comparison, never a mutation.</strong> §19.2 makes a completed review immutable, and
 * a failed one is still the record of what was attempted. The original keeps its findings and its
 * failure reason; the rerun is a fresh assessment that happens to share the inputs.
 */
class ComparisonRerunTest {

    private com.app.docreview.reviewcore.workflow.ComparisonFindingReader findingReader;

    private ComparisonRepository comparisons;
    private UploadedDocumentRepository documents;
    private StandardVersionRepository standardVersions;
    private JobDispatcher jobDispatcher;
    private ComparisonService service;

    @BeforeEach
    void setUp() {
        comparisons = Mockito.mock(ComparisonRepository.class);
        documents = Mockito.mock(UploadedDocumentRepository.class);
        standardVersions = Mockito.mock(StandardVersionRepository.class);
        jobDispatcher = Mockito.mock(JobDispatcher.class);
        findingReader = Mockito.mock(
                com.app.docreview.reviewcore.workflow.ComparisonFindingReader.class);
        service = new ComparisonService(comparisons, defaultLimits(), documents, standardVersions, jobDispatcher,
                findingReader);

        var document = Mockito.mock(UploadedDocument.class);
        when(document.readyForComparison()).thenReturn(true);
        when(documents.findByDocumentVersionId("PROC-1")).thenReturn(Optional.of(document));
        when(comparisons.save(any())).thenAnswer(i -> i.getArgument(0));
        seedStandard("sv-1", 40);
        seedStandard("sv-2", 30);
    }

    private void seedStandard(String id, int materialCount) {
        var version = new StandardVersion("std-" + id, "1.0", "sha", "key",
                "s.pdf", "application/pdf", 10, "u");
        version.setLifecycle(StandardLifecycle.ACTIVE);
        version.setIndexingState(StandardIndexingState.INDEXED);
        version.setMaterialRequirementCount(materialCount);
        when(standardVersions.findById(id)).thenReturn(Optional.of(version));
    }

    /** A failed comparison, as a reviewer would be looking at. */
    private Comparison failed(String owner) {
        var original = new Comparison("CMP-old", "PROC-1", List.of("sv-1", "sv-2"), owner);
        original.setState(ComparisonState.FAILED);
        original.setStateMessage("Coverage snapshot missing.");
        original.setComparisonGuidance("emergency change controls only");
        when(comparisons.findByPublicId("CMP-old")).thenReturn(Optional.of(original));
        return original;
    }

    @Test
    @DisplayName("the rerun carries the document, standards and guidance forward")
    void reusesTheOriginalInputs() {
        failed("reviewer@x");

        var rerun = service.rerun("CMP-old", "reviewer@x", "reviewer@x");

        assertThat(rerun.getPublicId()).isNotEqualTo("CMP-old");
        assertThat(rerun.getDocumentVersionId()).isEqualTo("PROC-1");
        assertThat(rerun.getStandardVersionIds()).containsExactly("sv-1", "sv-2");
        // Guidance MUST travel: it changes which requirements are surfaced (§19.2), so a rerun without
        // it is a different review that would silently produce different findings.
        assertThat(rerun.getComparisonGuidance()).isEqualTo("emergency change controls only");
        verify(jobDispatcher).dispatchRetrievalComparison(
                eq(rerun.getPublicId()), eq("PROC-1"), any(), eq("emergency change controls only"));
    }

    @Test
    @DisplayName("the original is left exactly as it was")
    void originalIsNotMutated() {
        var original = failed("reviewer@x");

        service.rerun("CMP-old", "reviewer@x", "reviewer@x");

        // §19.2. The earlier attempt remains the record of what was tried and why it failed — a reader
        // must still be able to see that, not find it overwritten by a later run.
        assertThat(original.getState()).isEqualTo(ComparisonState.FAILED);
        assertThat(original.getStateMessage()).isEqualTo("Coverage snapshot missing.");
        assertThat(original.getPublicId()).isEqualTo("CMP-old");
    }

    @Test
    @DisplayName("preconditions are re-checked, not inherited")
    void revalidatesStandards() {
        failed("reviewer@x");
        // The standard was comparable when the original ran and has since been archived — exactly the
        // duplicate-standard cleanup case. Inheriting the old validation would rerun against a standard
        // that is no longer eligible for retrieval.
        var archived = new StandardVersion("std-sv-2", "1.0", "sha", "key",
                "s.pdf", "application/pdf", 10, "u");
        archived.setLifecycle(StandardLifecycle.ARCHIVED);
        archived.setIndexingState(StandardIndexingState.INDEXED);
        when(standardVersions.findById("sv-2")).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.rerun("CMP-old", "reviewer@x", "reviewer@x"))
                .isInstanceOf(ApiException.class);
        verify(jobDispatcher, never()).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a comparison the caller cannot open cannot be rerun")
    void respectsVisibility() {
        failed("someone-else@x");

        // §33: the scoped read answers NOT FOUND rather than forbidden, so the response reveals nothing
        // either way. Rerunning through the unscoped `get` would have let a caller clone a review they
        // cannot see — and the new record would carry their own name.
        assertThatThrownBy(() -> service.rerun("CMP-old", "reviewer@x", "reviewer@x"))
                .isInstanceOf(ApiException.class);
        verify(jobDispatcher, never()).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an administrator (unrestricted scope) can rerun any comparison")
    void administratorScopeIsUnrestricted() {
        failed("someone-else@x");

        var rerun = service.rerun("CMP-old", "admin@x", null);

        assertThat(rerun.getDocumentVersionId()).isEqualTo("PROC-1");
        // Attributed to whoever RAN it, not to the original owner: the rerun is their assessment.
        assertThat(rerun.getCreatedBy()).isEqualTo("admin@x");
    }

    @Test
    @DisplayName("a targeted retry amends this comparison rather than creating another")
    void targetedRetryAmendsInPlace() {
        var original = failed("reviewer@x");
        when(findingReader.retryRequirement("CMP-old", "CHG-08-R2")).thenReturn(true);
        when(findingReader.read("CMP-old")).thenReturn(List.of());
        when(findingReader.readCoverage("CMP-old")).thenReturn(
                com.app.docreview.reviewcore.workflow.ComparisonFindingReader.CoverageFetch
                        .unavailable("no ledger in this fixture"));

        var amended = service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x");

        // SAME comparison — the point of a targeted retry. A new one would cost 187 calls and hand the
        // reviewer a second record to re-read.
        assertThat(amended.getPublicId()).isEqualTo("CMP-old");
        assertThat(amended).isSameAs(original);
        // And no new comparison was dispatched.
        verify(jobDispatcher, never()).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a retry that fails again is reported, not silently accepted")
    void failedRetryIsReported() {
        failed("reviewer@x");
        when(findingReader.retryRequirement("CMP-old", "CHG-08-R2")).thenReturn(false);

        // The requirement stays untested. Returning success would imply a recovery that did not happen
        // — and its coverage row would still say PENDING, contradicting the response.
        assertThatThrownBy(() -> service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("remains");
    }

    @Test
    @DisplayName("a retry cannot reach a comparison the caller cannot open")
    void retryRespectsVisibility() {
        failed("someone-else@x");

        assertThatThrownBy(() -> service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x"))
                .isInstanceOf(ApiException.class);
        // The worker is never even asked: the boundary is checked before any work is requested.
        verify(findingReader, never()).retryRequirement(any(), any());
    }

    @Test
    @DisplayName("every displayed number is recomputed after a retry, not just coverage")
    void retryUpdatesAllDisplayedCounts() {
        var original = failed("reviewer@x");
        // As the callback left them: stale the moment a retry recovers something.
        original.setEvaluatedRequirements(186);
        original.setTotalRequirements(187);
        original.setUnevaluatedRequirements(List.of("CHG-08-R2: Evaluation failed"));

        // The rebuilt ledger the worker returns: the requirement now has a verdict.
        var recovered = new com.app.docreview.contracts.comparison.CoverageLedger("CMP-old", List.of(
                new com.app.docreview.contracts.comparison.CoverageLedger.Entry(
                        "sv-1", "REQSET-V3", "CHG-08-R2",
                        com.app.docreview.contracts.comparison.CoverageLedger.CoverageState.EVALUATED,
                        null, "Emergency retrospective review")));
        when(findingReader.retryRequirement("CMP-old", "CHG-08-R2")).thenReturn(true);
        when(findingReader.read("CMP-old")).thenReturn(List.of());
        when(findingReader.readCoverage("CMP-old")).thenReturn(
                com.app.docreview.reviewcore.workflow.ComparisonFindingReader.CoverageFetch
                        .found(recovered));

        var amended = service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x");

        // FOUR displays, one truth. The header counter is set from the callback's completedUnits and a
        // retry sends no callback, so it stayed at 186/187 while the coverage panel below showed the
        // requirement recovered — two numbers describing the same thing, disagreeing on screen.
        assertThat(amended.getEvaluatedRequirements()).isEqualTo(1);
        assertThat(amended.getTotalRequirements()).isEqualTo(1);
        // And the NAMED list behind the top warning, which is the most specific claim on the page.
        assertThat(amended.getUnevaluatedRequirements())
                .as("a recovered requirement must stop being named as unevaluated")
                .isEmpty();
        assertThat(amended.getState()).isEqualTo(ComparisonState.COMPLETED);
        assertThat(amended.getStateMessage()).isNull();
    }

    @Test
    @DisplayName("a not-applicable requirement is not listed as unevaluated")
    void skippedIsNotReportedAsUnevaluated() {
        var original = failed("reviewer@x");
        var ledger = new com.app.docreview.contracts.comparison.CoverageLedger("CMP-old", List.of(
                new com.app.docreview.contracts.comparison.CoverageLedger.Entry(
                        "sv-1", "REQSET-V3", "CHG-09",
                        com.app.docreview.contracts.comparison.CoverageLedger.CoverageState.SKIPPED,
                        "Applies to Retention Schedule", "Records retention")));
        when(findingReader.retryRequirement("CMP-old", "CHG-08-R2")).thenReturn(true);
        when(findingReader.read("CMP-old")).thenReturn(List.of());
        when(findingReader.readCoverage("CMP-old")).thenReturn(
                com.app.docreview.reviewcore.workflow.ComparisonFindingReader.CoverageFetch
                        .found(ledger));

        var amended = service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x");

        // A governed exclusion is a DECISION, not a failure to evaluate. Listing it under "could not be
        // evaluated" would report a correct exclusion as a defect in the review.
        assertThat(amended.getUnevaluatedRequirements()).isEmpty();
        assertThat(original.getCoverageLedger().notApplicableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retry that leaves other gaps reports PARTIALLY_COMPLETED with a current message")
    void stateFollowsTheLedgerDownwardToo() {
        var original = failed("reviewer@x");
        original.setStateMessage("Coverage snapshot missing.");

        // One requirement recovered, another still untested.
        var partial = new com.app.docreview.contracts.comparison.CoverageLedger("CMP-old", List.of(
                new com.app.docreview.contracts.comparison.CoverageLedger.Entry(
                        "sv-1", "REQSET-V3", "CHG-08-R2",
                        com.app.docreview.contracts.comparison.CoverageLedger.CoverageState.EVALUATED,
                        null, "Emergency retrospective review"),
                new com.app.docreview.contracts.comparison.CoverageLedger.Entry(
                        "sv-1", "REQSET-V3", "CHG-11",
                        com.app.docreview.contracts.comparison.CoverageLedger.CoverageState.FAILED,
                        "Evaluation failed", "Post-change verification")));
        when(findingReader.retryRequirement("CMP-old", "CHG-08-R2")).thenReturn(true);
        when(findingReader.read("CMP-old")).thenReturn(List.of());
        when(findingReader.readCoverage("CMP-old")).thenReturn(
                com.app.docreview.reviewcore.workflow.ComparisonFindingReader.CoverageFetch
                        .found(partial));

        var amended = service.retryRequirement("CMP-old", "CHG-08-R2", "reviewer@x");

        // The state must FOLLOW the ledger, not accumulate. Previously the transition only ever moved
        // toward COMPLETED, so this comparison kept FAILED and a message about a missing coverage
        // snapshot that no longer applied.
        assertThat(amended.getState()).isEqualTo(ComparisonState.PARTIALLY_COMPLETED);
        assertThat(amended.getStateMessage())
                .as("the message must name the CURRENT shortfall, not the original one")
                .isEqualTo("1 of 2 requirements could not be evaluated.");
        assertThat(amended.getEvaluatedRequirements()).isEqualTo(1);
        assertThat(amended.getUnevaluatedRequirements()).containsExactly("CHG-11: Evaluation failed");
    }

    /**
     * A limits service returning the shipped defaults.
     *
     * <p>These tests are not about the cap, so the default is the honest stand-in: a mock returning
     * zero would reject every comparison and make unrelated assertions fail for a reason that has
     * nothing to do with what they test.
     */
    private static com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService defaultLimits() {
        var limits = org.mockito.Mockito.mock(
                com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService.class);
        org.mockito.Mockito.when(limits.current())
                .thenReturn(new com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfig());
        return limits;
    }
}
