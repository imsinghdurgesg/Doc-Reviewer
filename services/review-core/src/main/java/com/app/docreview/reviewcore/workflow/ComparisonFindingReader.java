package com.app.docreview.reviewcore.workflow;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Fetches validated findings from retrieval-comparison. Architecture §21, §22.3.
 *
 * <p>Findings are PULLED on callback rather than pushed in the callback body. §21 keeps job payloads
 * to references and counts, and a comparison over a large standard can produce hundreds of findings
 * with reason text — a body that size is retried, logged, and held in memory on both sides.
 *
 * <p>This is also the ownership boundary working as intended (§22.3): the worker owns
 * {@code atomic_comparison_results} as a working set, review-core copies the validated result into
 * the authoritative comparison, and from then on the comparison is immutable (§19.2). A recomputed
 * working set cannot retroactively change a completed review.
 */
@Component
@Slf4j
public class ComparisonFindingReader {

    private final RestClient retrievalComparison;

    public ComparisonFindingReader(JobDispatchProperties properties,
                                   InternalRestClientFactory clientFactory) {
        this.retrievalComparison = clientFactory.create(
                properties.retrievalComparison().baseUrl());
    }

    /**
     * Reads the findings for one comparison.
     *
     * <p>Returns empty rather than throwing on failure. The caller has already recorded the
     * comparison's terminal state, and losing that because a follow-up read failed would leave the
     * comparison stuck in progress forever — the sweep would then re-dispatch work that had already
     * completed and been paid for.
     */
    public List<Finding> read(String comparisonId) {
        try {
            var findings = retrievalComparison.get()
                    .uri("/internal/v1/comparison/{comparisonId}/findings", comparisonId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Finding>>() {});

            if (findings == null) {
                log.warn("Finding read for comparison {} returned no body", comparisonId);
                return List.of();
            }
            log.info("Read {} finding(s) for comparison {}", findings.size(), comparisonId);
            return findings;

        } catch (Exception e) {
            // Type only: the response body carries reason text quoting both documents (§10.5).
            log.error("Failed to read findings for comparison {}: {}. The comparison's state is "
                            + "recorded but its findings are empty; re-reading requires a rerun.",
                    comparisonId, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Asks the worker to re-evaluate ONE failed requirement. Architecture §18.4.
     *
     * <p>Returns false when the retry produced nothing, so the caller can say so rather than implying a
     * recovery that did not happen. A 409 from the worker is that case, not an error.
     */
    public boolean retryRequirement(String comparisonId, String requirementId) {
        try {
            retrievalComparison.post()
                    .uri("/internal/v1/comparison/{comparisonId}/requirements/{requirementId}/retry",
                            comparisonId, requirementId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // Type only (§35). Includes the 409 the worker returns when the retry failed again — a
            // legitimate outcome, so this is info rather than error.
            log.info("Retry of requirement {} in comparison {} did not succeed ({})",
                    requirementId, comparisonId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Reads the coverage ledger, or null when the worker has none. Architecture §17.6.
     *
     * <p>Null on any failure, including a 404. A missing ledger is materially different from an empty
     * one: empty asserts that no requirements were expected, which would present a comparison whose
     * coverage is simply unknown as having been fully covered — the exact misreading §17.6 exists to
     * prevent.
     */
    public CoverageFetch readCoverage(String comparisonId) {
        try {
            var ledger = retrievalComparison.get()
                    .uri("/internal/v1/comparison/{comparisonId}/coverage", comparisonId)
                    .retrieve()
                    .body(CoverageLedger.class);
            return ledger == null
                    ? CoverageFetch.unavailable("empty response body")
                    : CoverageFetch.found(ledger);

        } catch (HttpClientErrorException.NotFound e) {
            // THE ONLY path to "not recorded". The worker answered, definitively, that it holds no
            // ledger — which is the legacy case for a comparison that ran before persistence existed.
            log.info("Worker reports no coverage ledger for comparison {}", comparisonId);
            return CoverageFetch.notRecorded();

        } catch (Exception e) {
            // EVERYTHING ELSE IS A FAILURE TO LEARN, NOT AN ANSWER.
            //
            // A timeout, a 500, a connection refused — none of these say the ledger does not exist.
            // Mapping them to "not recorded" would render an outage as a settled fact about the
            // review, and a reader would conclude coverage was never captured when it may well have
            // been. That is the same class of silent misreading as showing zeros for a missing ledger.
            log.error("Could not read coverage for comparison {}: {}. Coverage is UNKNOWN, "
                            + "which is not the same as absent.",
                    comparisonId, e.getClass().getSimpleName());
            return CoverageFetch.unavailable(e.getClass().getSimpleName());
        }
    }

    /**
     * The outcome of asking the worker for a ledger. Architecture §17.6.
     *
     * <p>Three outcomes, not two, because "the worker says there is none" and "we could not ask" lead
     * to different conclusions. The first is a fact about the comparison; the second is a fact about
     * the infrastructure, and presenting it as the first is how an outage becomes a false coverage
     * claim.
     *
     * @param ledger  present only when {@code FOUND}
     * @param failure the failure type, for logging. Null unless {@code UNAVAILABLE}.
     */
    public record CoverageFetch(Outcome outcome, CoverageLedger ledger, String failure) {

        public enum Outcome {
            /** The worker returned a ledger. */
            FOUND,
            /** The worker answered 404: it genuinely holds none. The legacy case. */
            NOT_RECORDED,
            /** The worker could not be asked, or answered unusably. Coverage is unknown. */
            UNAVAILABLE
        }

        public static CoverageFetch found(CoverageLedger ledger) {
            return new CoverageFetch(Outcome.FOUND, ledger, null);
        }

        public static CoverageFetch notRecorded() {
            return new CoverageFetch(Outcome.NOT_RECORDED, null, null);
        }

        public static CoverageFetch unavailable(String failure) {
            return new CoverageFetch(Outcome.UNAVAILABLE, null, failure);
        }
    }
}
