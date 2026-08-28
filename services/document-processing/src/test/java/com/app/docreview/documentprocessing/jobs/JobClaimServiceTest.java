package com.app.docreview.documentprocessing.jobs;

import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.contracts.job.DocumentProcessingCommand;
import com.app.docreview.contracts.job.JobEnvelope;
import com.app.docreview.contracts.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Worker job claims. Architecture §21 step 3, §39 rule 23.
 *
 * <p>The claim is what makes a repeated dispatch a no-op. §21 requires it, and the failure it prevents
 * is concrete: the same document parsed twice, writing two sets of spans whose IDs collide.
 *
 * <p>The key case is {@link #duplicateKeyIsSuccessNotFailure()} — a {@code DuplicateKeyException} is
 * the EXPECTED outcome of a re-dispatch, because the insert races on the unique index rather than
 * checking first. Treating it as an error would turn every legitimate re-dispatch into a 500.
 */
class JobClaimServiceTest {

    private WorkerJobRepository jobs;
    private JobClaimService service;

    @BeforeEach
    void setUp() {
        jobs = Mockito.mock(WorkerJobRepository.class);
        service = new JobClaimService(jobs);
    }

    private DocumentProcessingCommand command() {
        return new DocumentProcessingCommand(
                JobEnvelope.of("job-1", JobType.DOCUMENT_PROCESSING, "DOC-V1", 1, Instant.now()),
                "DOC-V1", DocumentType.UPLOADED_PROCESS,
                "docreview-user-documents", "uploads/DOC-1/source.pdf", "application/pdf",
                "http://review-core:8080/internal/v1/callbacks/document-processing");
    }

    @Test
    @DisplayName("a first dispatch creates the claim")
    void firstDispatchCreatesClaim() {
        when(jobs.insert(any(WorkerJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var claim = service.claim(command());

        assertThat(claim.created()).isTrue();
        assertThat(claim.toResponse().duplicate()).isFalse();
        assertThat(claim.job().getDocumentVersionId()).isEqualTo("DOC-V1");
    }

    @Test
    @DisplayName("a duplicate key returns the existing claim as success, not an error")
    void duplicateKeyIsSuccessNotFailure() {
        var existing = new WorkerJob("job-1", "DOCUMENT_PROCESSING:DOC-V1", "DOC-V1",
                "UPLOADED_PROCESS", "docreview-user-documents", "uploads/DOC-1/source.pdf",
                "http://review-core:8080/internal/v1/callbacks/document-processing");

        when(jobs.insert(any(WorkerJob.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        when(jobs.findByIdempotencyKey("DOCUMENT_PROCESSING:DOC-V1"))
                .thenReturn(Optional.of(existing));

        var claim = service.claim(command());

        assertThat(claim.created())
                .as("a re-dispatch must not start the work a second time")
                .isFalse();
        assertThat(claim.toResponse().duplicate()).isTrue();
        assertThat(claim.job()).isSameAs(existing);
    }

    @Test
    @DisplayName("the idempotency key comes from the envelope, not the attempt number")
    void keyIsAttemptIndependent() {
        when(jobs.insert(any(WorkerJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Attempt 1 and attempt 5 of the same work must claim the SAME key, or a retry would look
        // like new work and duplicate the artifacts.
        var attempt1 = JobEnvelope.of("job-1", JobType.DOCUMENT_PROCESSING, "DOC-V1", 1, Instant.now());
        var attempt5 = JobEnvelope.of("job-1", JobType.DOCUMENT_PROCESSING, "DOC-V1", 5, Instant.now());

        assertThat(attempt1.idempotencyKey()).isEqualTo(attempt5.idempotencyKey());

        var claim = service.claim(command());
        assertThat(claim.job().getIdempotencyKey()).isEqualTo("DOCUMENT_PROCESSING:DOC-V1");
    }
}
