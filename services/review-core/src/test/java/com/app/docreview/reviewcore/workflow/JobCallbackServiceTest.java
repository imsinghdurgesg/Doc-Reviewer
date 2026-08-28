package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.job.ComparisonState;
import com.app.docreview.contracts.job.JobCallback;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.contracts.job.JobType;
import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardRepository;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJob;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker completion callbacks. Architecture §21 steps 6–7, §12.8, §39 rule 23.
 *
 * <p>The guarantee under test is that a callback can arrive TWICE and the second one changes nothing.
 * A worker resends whenever it cannot confirm delivery, so this is a normal event, not an edge case —
 * and a non-idempotent handler would let a stale duplicate overwrite a finished result.
 */
class JobCallbackServiceTest {

    private ProcessingJobRepository jobs;
    private StandardVersionRepository versions;
    private UploadedDocumentRepository documents;
    private ComparisonRepository comparisons;
    private ComparisonFindingReader findingReader;
    private StandardRepository standards;
    private JobDispatcher jobDispatcher;
    private JobCallbackService service;

    private static final String JOB_ID = "job-1";
    private static final String KEY = "STANDARD_INDEXING:STD-V2.4";

    @BeforeEach
    void setUp() {
        jobs = Mockito.mock(ProcessingJobRepository.class);
        versions = Mockito.mock(StandardVersionRepository.class);
        documents = Mockito.mock(UploadedDocumentRepository.class);
        comparisons = Mockito.mock(ComparisonRepository.class);
        findingReader = Mockito.mock(ComparisonFindingReader.class);
        standards = Mockito.mock(StandardRepository.class);
        jobDispatcher = Mockito.mock(JobDispatcher.class);
        service = new JobCallbackService(jobs, versions, documents, comparisons, findingReader,
                standards, jobDispatcher);

        // A coverage snapshot is required for a comparison to reach a terminal state (§17.6), and
        // these fixtures build real Comparison objects, which are stamped with the coverage schema.
        // Stubbed here rather than per-test: every one of them asserts something about a COMPLETED or
        // PARTIALLY_COMPLETED result, so all of them depend on the invariant being satisfiable.
        Mockito.when(findingReader.readCoverage(Mockito.anyString()))
                .thenReturn(ComparisonFindingReader.CoverageFetch.found(
                        new CoverageLedger("CMP-1", List.of())));
    }

    private ProcessingJob job(JobState state) {
        var job = new ProcessingJob("STANDARD_INDEXING", KEY,
                Map.of("standardId", "GRC-CHG", "standardVersionId", "STD-V2.4"), 1, 3);
        job.setState(state);
        // ProcessingJob's ID is assigned by Mongo on insert; set it directly for the lookup.
        try {
            var field = ProcessingJob.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(job, JOB_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return job;
    }

    private JobCallback callback(JobState terminalState, Map<String, String> results) {
        return new JobCallback(JOB_ID, JobType.STANDARD_INDEXING, KEY, terminalState,
                "INDEXED", 12, 14, null, List.of(), results);
    }

    @Nested
    @DisplayName("idempotency (§21, §39 rule 23)")
    class Idempotency {

        @Test
        @DisplayName("a callback for an already-terminal job is ignored")
        void duplicateIgnored() {
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(JobState.COMPLETED)));

            var outcome = service.apply(callback(JobState.COMPLETED, Map.of()));

            assertThat(outcome).isEqualTo(JobCallbackService.Outcome.IGNORED_DUPLICATE);
            // Nothing is written: re-applying would move a finished job's timestamps.
            verify(jobs, never()).save(any());
            verify(versions, never()).save(any());
        }

        @Test
        @DisplayName("a mismatched idempotency key is rejected rather than applied")
        void keyMismatchRejected() {
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(JobState.DISPATCHED)));

            var mismatched = new JobCallback(JOB_ID, JobType.STANDARD_INDEXING,
                    "STANDARD_INDEXING:SOMETHING-ELSE", JobState.COMPLETED,
                    "INDEXED", 1, 1, null, List.of(), Map.of());

            assertThat(service.apply(mismatched))
                    .as("a worker reporting on different work than the job describes must not win")
                    .isEqualTo(JobCallbackService.Outcome.KEY_MISMATCH);
            verify(jobs, never()).save(any());
        }

        @Test
        @DisplayName("a callback for an unknown job is reported, not resurrected")
        void unknownJob() {
            when(jobs.findById(JOB_ID)).thenReturn(Optional.empty());

            assertThat(service.apply(callback(JobState.COMPLETED, Map.of())))
                    .isEqualTo(JobCallbackService.Outcome.UNKNOWN_JOB);
            verify(jobs, never()).save(any());
        }
    }

    @Nested
    @DisplayName("standard indexing state (§8.1, §12.8)")
    class IndexingState {

        private StandardVersion version;

        @BeforeEach
        void seedVersion() {
            version = new StandardVersion("GRC-CHG", "2.4", "sha", "active/x/source.pdf",
                    "source.pdf", "application/pdf", 1024, "admin");
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(job(JobState.DISPATCHED)));
            when(versions.findById("STD-V2.4")).thenReturn(Optional.of(version));
        }

        @Test
        @DisplayName("COMPLETED indexing marks the version INDEXED")
        void completedMarksIndexed() {
            service.apply(callback(JobState.COMPLETED, Map.of(
                    JobCallback.RESULT_REQUIREMENT_SET_VERSION, "REQSET-V1",
                    JobCallback.RESULT_REQUIREMENT_COUNT, "12",
                    JobCallback.RESULT_MATERIAL_REQUIREMENT_COUNT, "9")));

            assertThat(version.getIndexingState()).isEqualTo(StandardIndexingState.INDEXED);
            assertThat(version.getRequirementSetVersion()).isEqualTo("REQSET-V1");
            assertThat(version.getRequirementCount()).isEqualTo(12);
            assertThat(version.getMaterialRequirementCount()).isEqualTo(9);
            assertThat(version.getIndexedAt()).isNotNull();
        }

        @Test
        @DisplayName("PARTIALLY_COMPLETED indexing marks NEEDS_REVIEW, never INDEXED")
        void partialMarksNeedsReview() {
            service.apply(callback(JobState.PARTIALLY_COMPLETED, Map.of()));

            // §12.8: an extraction that failed validation must not publish as usable controls, and
            // §8.1 then refuses to activate the version — which is the point.
            assertThat(version.getIndexingState()).isEqualTo(StandardIndexingState.NEEDS_REVIEW);
            assertThat(version.comparable()).isFalse();
        }

        @Test
        @DisplayName("FAILED indexing marks the version FAILED")
        void failedMarksFailed() {
            service.apply(callback(JobState.FAILED, Map.of()));

            assertThat(version.getIndexingState()).isEqualTo(StandardIndexingState.FAILED);
        }

        @Test
        @DisplayName("a malformed count is ignored without failing the callback")
        void malformedCountToleratedOnIndexing() {
            // The job's terminal state matters far more than one diagnostic counter.
            var outcome = service.apply(callback(JobState.COMPLETED, Map.of(
                    JobCallback.RESULT_REQUIREMENT_COUNT, "not-a-number")));

            assertThat(outcome).isEqualTo(JobCallbackService.Outcome.APPLIED);
            assertThat(version.getIndexingState()).isEqualTo(StandardIndexingState.INDEXED);
            assertThat(version.getRequirementCount()).isZero();
        }
    }

    @Nested
    @DisplayName("document processing (§14, §17.5)")
    class DocumentProcessing {

        private ProcessingJob docJob() {
            var job = new ProcessingJob("DOCUMENT_PROCESSING", "DOCUMENT_PROCESSING:PROC-V1",
                    Map.of("documentVersionId", "PROC-V1", "documentType", "UPLOADED_PROCESS"), 1, 3);
            job.setState(JobState.DISPATCHED);
            try {
                var field = ProcessingJob.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(job, JOB_ID);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return job;
        }

        private UploadedDocument document() {
            return new UploadedDocument("PROC-V1", "process.pdf", "application/pdf", 2048,
                    "sha", "docreview-user-documents", "uploads/PROC-V1/process.pdf", "reviewer");
        }

        @Test
        @DisplayName("span and chunk counts are recorded, making the document comparable")
        void recordsCountsAndReadiness() {
            var document = document();
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(docJob()));
            when(documents.findByDocumentVersionId("PROC-V1")).thenReturn(Optional.of(document));

            service.apply(new JobCallback(JOB_ID, JobType.DOCUMENT_PROCESSING,
                    "DOCUMENT_PROCESSING:PROC-V1", JobState.COMPLETED, "COMPLETED", 42, 42,
                    null, List.of(), Map.of(
                            JobCallback.RESULT_SPAN_COUNT, "42",
                            JobCallback.RESULT_CHUNK_COUNT, "7",
                            JobCallback.RESULT_PAGE_COUNT, "12",
                            JobCallback.RESULT_CHUNKING_STRATEGY_VERSION, "SEMANTIC-V1")));

            assertThat(document.getSpanCount()).isEqualTo(42);
            assertThat(document.getChunkCount()).isEqualTo(7);
            assertThat(document.getPageCount()).isEqualTo(12);
            assertThat(document.getChunkingStrategyVersion()).isEqualTo("SEMANTIC-V1");
            // The gate that stops a comparison running against an unparsed document (§17.5).
            assertThat(document.readyForComparison()).isTrue();
        }

        @Test
        @DisplayName("zero spans leaves the document NOT comparable")
        void zeroSpansIsNotComparable() {
            var document = document();
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(docJob()));
            when(documents.findByDocumentVersionId("PROC-V1")).thenReturn(Optional.of(document));

            // A scanned PDF parses without error and yields no spans. Comparing it would report
            // every requirement MISSING, which reads as total non-compliance rather than
            // "unreadable document" (§17.5).
            service.apply(new JobCallback(JOB_ID, JobType.DOCUMENT_PROCESSING,
                    "DOCUMENT_PROCESSING:PROC-V1", JobState.COMPLETED, "COMPLETED", 0, 0,
                    null, List.of(), Map.of(JobCallback.RESULT_SPAN_COUNT, "0")));

            assertThat(document.readyForComparison()).isFalse();
        }

        @Test
        @DisplayName("a standard's processing callback does not fail for want of an upload record")
        void standardProcessingIsNotAnError() {
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(docJob()));
            when(documents.findByDocumentVersionId("PROC-V1")).thenReturn(Optional.empty());

            // Standards are tracked on StandardVersion, not UploadedDocument, so an absent upload is
            // expected rather than a failure.
            assertThat(service.apply(new JobCallback(JOB_ID, JobType.DOCUMENT_PROCESSING,
                    "DOCUMENT_PROCESSING:PROC-V1", JobState.COMPLETED, "COMPLETED", 1, 1,
                    null, List.of(), Map.of())))
                    .isEqualTo(JobCallbackService.Outcome.APPLIED);
        }
    }

    @Nested
    @DisplayName("comparison results (§18.4, §19.2)")
    class ComparisonResults {

        private ProcessingJob comparisonJob() {
            var job = new ProcessingJob("RETRIEVAL_COMPARISON", "RETRIEVAL_COMPARISON:CMP-1",
                    Map.of("comparisonId", "CMP-1", "documentVersionId", "PROC-V1"), 1, 3);
            job.setState(JobState.DISPATCHED);
            try {
                var field = ProcessingJob.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(job, JOB_ID);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return job;
        }

        private JobCallback callback(JobState state, Map<String, String> results) {
            return new JobCallback(JOB_ID, JobType.RETRIEVAL_COMPARISON,
                    "RETRIEVAL_COMPARISON:CMP-1", state, state.name(), 197, 200,
                    state == JobState.PARTIALLY_COMPLETED ? "3 of 200 could not be evaluated." : null,
                    List.of(), results);
        }

        @Test
        @DisplayName("the §19.2 reproducibility snapshot is stored")
        void storesReproducibilitySnapshot() {
            var comparison = new Comparison("CMP-1", "PROC-V1", List.of("STD-V2.4"), "reviewer");
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(comparisonJob()));
            when(comparisons.findByPublicId("CMP-1")).thenReturn(Optional.of(comparison));
            when(findingReader.read("CMP-1")).thenReturn(List.of());

            service.apply(callback(JobState.COMPLETED, Map.of(
                    JobCallback.RESULT_COMPARISON_MODEL_ALIAS, "docreview-comparison",
                    JobCallback.RESULT_COMPARISON_PROMPT_VERSION, "COMPARE-V1",
                    JobCallback.RESULT_COMPARISON_SCHEMA_VERSION, "COMPARE-SCHEMA-V1",
                    JobCallback.RESULT_RETRIEVAL_STRATEGY_VERSION, "RRF-V1",
                    JobCallback.RESULT_REQUIREMENT_SET_VERSION, "REQSET-V1",
                    JobCallback.RESULT_EMBEDDING_DIMENSIONS, "16")));

            // §39 rule 30: without these a stored finding cannot be attributed to what produced it.
            assertThat(comparison.getComparisonModelAlias()).isEqualTo("docreview-comparison");
            assertThat(comparison.getComparisonPromptVersion()).isEqualTo("COMPARE-V1");
            assertThat(comparison.getComparisonSchemaVersion()).isEqualTo("COMPARE-SCHEMA-V1");
            assertThat(comparison.getRetrievalStrategyVersion()).isEqualTo("RRF-V1");
            assertThat(comparison.getRequirementSetVersion()).isEqualTo("REQSET-V1");
            assertThat(comparison.getEmbeddingDimensions()).isEqualTo(16);
        }

        @Test
        @DisplayName("PARTIALLY_COMPLETED keeps its findings and stays visibly partial (§18.4)")
        void partialKeepsFindings() {
            var comparison = new Comparison("CMP-1", "PROC-V1", List.of("STD-V2.4"), "reviewer");
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(comparisonJob()));
            when(comparisons.findByPublicId("CMP-1")).thenReturn(Optional.of(comparison));
            when(findingReader.read("CMP-1")).thenReturn(List.of());

            service.apply(callback(JobState.PARTIALLY_COMPLETED, Map.of()));

            // §18.4: 197 of 200 succeeding is neither COMPLETED (which hides the gap) nor FAILED
            // (which discards the work). The counts make the gap inspectable.
            assertThat(comparison.getState()).isEqualTo(ComparisonState.PARTIALLY_COMPLETED);
            assertThat(comparison.getEvaluatedRequirements()).isEqualTo(197);
            assertThat(comparison.getTotalRequirements()).isEqualTo(200);
            assertThat(comparison.getStateMessage()).contains("3 of 200");
        }

        @Test
        @DisplayName("the requirements that failed are persisted, not just their count (§18.4)")
        void storesUnevaluatedRequirements() {
            var comparison = new Comparison("CMP-1", "PROC-V1", List.of("STD-V2.4"), "reviewer");
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(comparisonJob()));
            when(comparisons.findByPublicId("CMP-1")).thenReturn(Optional.of(comparison));
            when(findingReader.read("CMP-1")).thenReturn(List.of());

            var unitErrors = List.of(
                    "CHG-04-2: PARTIAL requires at least one unsatisfied clause",
                    "CHG-07-1: IllegalStateException");
            service.apply(new JobCallback(JOB_ID, JobType.RETRIEVAL_COMPARISON,
                    "RETRIEVAL_COMPARISON:CMP-1", JobState.PARTIALLY_COMPLETED,
                    "PARTIALLY_COMPLETED", 198, 200,
                    "2 of 200 requirements could not be evaluated.", unitErrors, Map.of()));

            // These arrived from the worker and were previously DROPPED, leaving the reviewer with a
            // count and no way to learn which control was affected.
            assertThat(comparison.getUnevaluatedRequirements()).isEqualTo(unitErrors);
        }

        @Test
        @DisplayName("a FAILED comparison does not attempt to read findings")
        void failedSkipsFindingRead() {
            var comparison = new Comparison("CMP-1", "PROC-V1", List.of("STD-V2.4"), "reviewer");
            when(jobs.findById(JOB_ID)).thenReturn(Optional.of(comparisonJob()));
            when(comparisons.findByPublicId("CMP-1")).thenReturn(Optional.of(comparison));

            service.apply(callback(JobState.FAILED, Map.of()));

            assertThat(comparison.getState()).isEqualTo(ComparisonState.FAILED);
            verify(findingReader, never()).read(any());
        }
    }
}
