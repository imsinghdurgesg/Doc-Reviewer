package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.document.DocumentType;
import com.app.docreview.contracts.job.JobState;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJob;
import com.app.docreview.reviewcore.workflow.domain.ProcessingJobRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job dispatch. Architecture §9.2, §21, §39 rules 22–23.
 *
 * <p>Every test here pins a guarantee that is invisible in normal operation and only shows up as data
 * corruption when broken: ordering of the durable write against the network call, and suppression of
 * duplicate work.
 *
 * <p>WireMock rather than a mocked RestClient: the ordering guarantee is about what has been persisted
 * at the moment the HTTP call is made, and only a real request lets the assertion observe that.
 */
class HttpJobDispatcherTest {

    private WireMockServer worker;
    private RecordingJobRepository jobs;
    private HttpJobDispatcher dispatcher;

    private static final String TOKEN = "test-internal-token";

    @BeforeEach
    void setUp() {
        worker = new WireMockServer(options().dynamicPort());
        worker.start();

        String baseUrl = "http://localhost:" + worker.port();
        var properties = new JobDispatchProperties(
                new JobDispatchProperties.Worker(baseUrl),
                new JobDispatchProperties.Worker(baseUrl),
                TOKEN,
                "http://review-core:8080",
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                3, Duration.ofMinutes(15));

        jobs = new RecordingJobRepository();
        dispatcher = new HttpJobDispatcher(jobs, properties,
                new InternalRestClientFactory(properties));
    }

    @AfterEach
    void tearDown() {
        worker.stop();
    }

    private void workerAccepts() {
        worker.stubFor(post(urlPathEqualTo("/internal/v1/document-processing/jobs"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"jobId\":\"j\",\"workerJobId\":\"w\",\"duplicate\":false}")));
    }

    private String dispatchDocument() {
        return dispatcher.dispatchDocumentProcessing(
                "DOC-V1", DocumentType.UPLOADED_PROCESS, "docreview-user-documents",
                "uploads/DOC-1/source.pdf", "application/pdf");
    }

    /**
     * Reviewer assessment context reaches the worker, and survives a re-dispatch. Architecture §15.1,
     * §19.2.
     *
     * <p>The failure mode is silent: guidance threads through five layers, and a layer that accepts it
     * and drops it compiles, runs, and produces a comparison that looks correct while ignoring what the
     * reviewer asked for. Nothing on screen would show it. So these assert on the wire, not on a mock.
     */
    @Nested
    @DisplayName("reviewer assessment context (§15.1, §19.2)")
    class AssessmentContext {

        private void comparisonWorkerAccepts() {
            worker.stubFor(post(urlPathEqualTo("/internal/v1/comparison/jobs"))
                    .willReturn(aResponse().withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"jobId\":\"j\",\"workerJobId\":\"w\",\"duplicate\":false}")));
        }

        @Test
        @DisplayName("guidance is sent to the worker in the command body")
        void guidanceReachesTheWorker() {
            comparisonWorkerAccepts();

            dispatcher.dispatchRetrievalComparison(
                    "CMP-1", "DOC-V1", List.of(), "emergency change controls only");

            worker.verify(postRequestedFor(urlPathEqualTo("/internal/v1/comparison/jobs"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                            .matchingJsonPath("$.guidance", equalTo("emergency change controls only"))));
        }

        @Test
        @DisplayName("a re-dispatch carries the same guidance, not null")
        void redispatchPreservesGuidance() {
            // THE reason guidance is persisted on the payload. A recovery sweep that rebuilt the
            // command without it would re-run the comparison under a different retrieval focus than
            // the reviewer asked for, and §19.2 requires a replayed job to reproduce the original.
            comparisonWorkerAccepts();
            String jobId = dispatcher.dispatchRetrievalComparison(
                    "CMP-1", "DOC-V1", List.of(), "third-party onboarding");
            worker.resetRequests();

            dispatcher.redispatch(jobs.findById(jobId).orElseThrow());

            worker.verify(postRequestedFor(urlPathEqualTo("/internal/v1/comparison/jobs"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                            .matchingJsonPath("$.guidance", equalTo("third-party onboarding"))));
        }

        @Test
        @DisplayName("absent guidance stays absent through a re-dispatch")
        void noGuidanceIsNotInvented() {
            // Most comparisons have none, and legacy job records predate the field entirely. A
            // re-dispatch must not turn a missing value into an empty string that then gets appended
            // to a retrieval profile.
            comparisonWorkerAccepts();
            String jobId = dispatcher.dispatchRetrievalComparison("CMP-2", "DOC-V2", List.of(), null);

            var job = jobs.findById(jobId).orElseThrow();
            assertThat(job.getPayload())
                    .as("a null must not be persisted as a present key")
                    .doesNotContainKey("comparisonGuidance");

            worker.resetRequests();
            dispatcher.redispatch(job);

            worker.verify(postRequestedFor(urlPathEqualTo("/internal/v1/comparison/jobs"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                            .matchingJsonPath("$.guidance",
                                    com.github.tomakehurst.wiremock.client.WireMock.absent())));
        }
    }

    @Nested
    @DisplayName("persist before dispatch (§21, §39 rule 22)")
    class PersistBeforeDispatch {

        @Test
        @DisplayName("the job is already saved when the worker is called")
        void jobPersistedBeforeHttpCall() {
            workerAccepts();
            // Records how many saves had happened at the moment the request arrived. If dispatch
            // came first this would be zero, and a crash there would leave untracked work.
            jobs.recordSaveCountOnRequest(worker);

            dispatchDocument();

            assertThat(jobs.saveCountAtFirstRequest)
                    .as("job must be durable before the worker is contacted")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a dispatch failure leaves the job PENDING for the sweep, not FAILED")
        void dispatchFailureStaysRecoverable() {
            // No stub: the worker answers 404, which the client treats as an error.
            String jobId = dispatchDocument();

            var job = jobs.findById(jobId).orElseThrow();
            assertThat(job.getState())
                    .as("a down worker must not lose the job")
                    .isEqualTo(JobState.PENDING);
            assertThat(job.getAttempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("the caller still gets a job ID when dispatch fails")
        void callerNotFailedByWorkerOutage() {
            // The upload succeeded; only the background dispatch failed. Propagating would tell the
            // user their upload failed when it did not.
            assertThat(dispatchDocument()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("idempotency (§21, §39 rule 23)")
    class Idempotency {

        @Test
        @DisplayName("dispatching the same subject twice creates one job and one call")
        void duplicateDispatchIsNoOp() {
            workerAccepts();

            String first = dispatchDocument();
            String second = dispatchDocument();

            assertThat(second).isEqualTo(first);
            assertThat(jobs.saved).hasSize(1);
            worker.verify(1, postRequestedFor(
                    urlPathEqualTo("/internal/v1/document-processing/jobs")));
        }

        @Test
        @DisplayName("the idempotency key is derived from job type and subject, not the attempt")
        void keyIsAttemptIndependent() {
            workerAccepts();
            dispatchDocument();

            assertThat(jobs.saved.get(0).getIdempotencyKey())
                    .as("an attempt-scoped key would make every retry look like new work")
                    .isEqualTo("DOCUMENT_PROCESSING:DOC-V1");
        }
    }

    @Nested
    @DisplayName("dispatch mechanics")
    class Mechanics {

        @Test
        @DisplayName("the internal service token is presented to the worker")
        void tokenPresented() {
            workerAccepts();
            dispatchDocument();

            worker.verify(postRequestedFor(urlPathEqualTo("/internal/v1/document-processing/jobs"))
                    .withHeader(InternalRestClientFactory.TOKEN_HEADER, equalTo(TOKEN)));
        }

        @Test
        @DisplayName("a successful dispatch marks the job DISPATCHED")
        void successMarksDispatched() {
            workerAccepts();
            String jobId = dispatchDocument();

            var job = jobs.findById(jobId).orElseThrow();
            assertThat(job.getState()).isEqualTo(JobState.DISPATCHED);
            assertThat(job.getDispatchedAt()).isNotNull();
        }

        @Test
        @DisplayName("the callback URL is absolute and routed by job type (§20.3)")
        void callbackUrlPerJobType() {
            workerAccepts();
            dispatchDocument();

            worker.verify(postRequestedFor(urlPathEqualTo("/internal/v1/document-processing/jobs"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(
                            "http://review-core:8080/internal/v1/callbacks/document-processing")));
        }

        @Test
        @DisplayName("the payload carries references only, never document bytes (§20.3)")
        void payloadHoldsReferencesOnly() {
            workerAccepts();
            dispatchDocument();

            assertThat(jobs.saved.get(0).getPayload())
                    .containsEntry("documentVersionId", "DOC-V1")
                    .containsEntry("sourceObjectKey", "uploads/DOC-1/source.pdf");
        }
    }

    @Nested
    @DisplayName("retry bounds (§18.4)")
    class RetryBounds {

        @Test
        @DisplayName("a job that exhausts its attempts is marked FAILED, not retried forever")
        void exhaustedAttemptsFail() {
            // Pre-seed a job already at its attempt limit, then let the sweep re-dispatch it into a
            // worker that is not stubbed.
            var job = new ProcessingJob("DOCUMENT_PROCESSING", "DOCUMENT_PROCESSING:DOC-V9",
                    Map.of("documentVersionId", "DOC-V9",
                            "documentType", "UPLOADED_PROCESS",
                            "sourceBucket", "docreview-user-documents",
                            "sourceObjectKey", "uploads/DOC-9/source.pdf"),
                    1, 3);
            job.setAttempt(3);
            jobs.save(job);

            dispatcher.redispatch(job);

            assertThat(job.getState())
                    .as("retries must stop rather than loop (§18.4)")
                    .isEqualTo(JobState.FAILED);
            assertThat(job.getFailureReason()).contains("Dispatch failed after");
        }
    }

    /**
     * In-memory repository that records saves in order.
     *
     * <p>A hand-written fake rather than a Mockito mock because the assertions are about ORDER and
     * ACCUMULATED state — how many saves had happened by the time the HTTP request arrived. Stubbing
     * that on a mock reimplements a map with extra steps.
     */
    private static final class RecordingJobRepository implements ProcessingJobRepository {

        private final Map<String, ProcessingJob> byId = new HashMap<>();
        private final Map<String, ProcessingJob> byKey = new HashMap<>();
        final List<ProcessingJob> saved = new ArrayList<>();
        private int idSequence;
        int saveCountAtFirstRequest = -1;

        /** Captures the save count at the moment the worker receives its first request. */
        void recordSaveCountOnRequest(WireMockServer server) {
            server.addMockServiceRequestListener((request, response) -> {
                if (saveCountAtFirstRequest < 0) {
                    saveCountAtFirstRequest = saved.size();
                }
            });
        }

        @Override
        public Optional<ProcessingJob> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }

        @Override
        public List<ProcessingJob> findByStateInAndUpdatedAtBefore(
                List<JobState> states, java.time.Instant before) {
            return byId.values().stream()
                    .filter(j -> states.contains(j.getState()))
                    .filter(j -> j.getUpdatedAt().isBefore(before))
                    .toList();
        }

        @Override
        public <S extends ProcessingJob> S save(S entity) {
            if (entity.getId() == null) {
                // Mongo assigns the ID on insert; mirror that so the dispatcher's return value is
                // meaningful.
                try {
                    var field = ProcessingJob.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(entity, "job-" + (++idSequence));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                saved.add(entity);
            }
            byId.put(entity.getId(), entity);
            byKey.put(entity.getIdempotencyKey(), entity);
            return entity;
        }

        @Override
        public Optional<ProcessingJob> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        // ---- Unused MongoRepository surface -------------------------------------------------
        // Implemented as failures rather than no-ops: a test that starts depending on one of these
        // should say so loudly instead of silently observing an empty result.

        @Override public <S extends ProcessingJob> List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ProcessingJob> findAll() {
            return List.copyOf(byId.values());
        }
        @Override public List<ProcessingJob> findAllById(Iterable<String> ids) {
            throw new UnsupportedOperationException();
        }
        @Override public long count() {
            return byId.size();
        }
        @Override public void deleteById(String id) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(ProcessingJob entity) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAllById(Iterable<? extends String> ids) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAll(Iterable<? extends ProcessingJob> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAll() {
            throw new UnsupportedOperationException();
        }
        @Override public boolean existsById(String id) {
            return byId.containsKey(id);
        }
        @Override public List<ProcessingJob> findAll(org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException();
        }
        @Override public org.springframework.data.domain.Page<ProcessingJob> findAll(
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> S insert(S entity) {
            return save(entity);
        }
        @Override public <S extends ProcessingJob> List<S> insert(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> List<S> findAll(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> long count(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob> boolean exists(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends ProcessingJob, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery
                        .FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }
}
