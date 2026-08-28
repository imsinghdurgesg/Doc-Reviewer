package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.comparison.CoverageLedger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import com.app.docreview.contracts.comparison.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves working comparison results to review-core. Architecture §22.3, §21.
 *
 * <p>The read side of the results-ownership boundary. This service owns
 * {@code atomic_comparison_results} as a working set (§8.3 forbids it owning the authoritative
 * comparison), and review-core copies the validated findings into the comparison it owns when the
 * completion callback arrives.
 *
 * <p>A pull endpoint rather than a larger callback body, because §21 keeps job payloads to references
 * and counts — hundreds of findings with reason text is not a payload that should be retried or
 * logged.
 *
 * <p>Reachable only with the internal service token (see {@link InternalTokenFilter}).
 */
@RestController
@RequestMapping("/internal/v1/comparison")
@RequiredArgsConstructor
@Slf4j
public class ComparisonResultController {

    private final ComparisonResultWriter resultWriter;
    private final ComparisonJobRunner comparisonRunner;
    private final WorkerJobRepository jobs;

    @GetMapping("/{comparisonId}/findings")
    public List<Finding> findings(@PathVariable String comparisonId) {
        var findings = resultWriter.read(comparisonId);
        log.debug("Serving {} finding(s) for comparison {}", findings.size(), comparisonId);
        return findings;
    }

    /**
     * Re-evaluates ONE failed requirement and amends the comparison. Architecture §18.4, §19.2.
     *
     * <p>1 model call instead of the 187 a full rerun costs, and the reviewer keeps the comparison they
     * were already reading rather than being handed a new one to re-read.
     *
     * <p>The amended finding is STAMPED with the versions it ran under, because the comparison's header
     * records the versions of its original run. Without the stamp a reader could not tell why one
     * finding differs from its neighbours.
     *
     * <p>The coverage ledger is rebuilt so the recovery is reflected: leaving it would show the
     * requirement as untested while a finding for it exists, which is the contradiction §17.6 exists to
     * prevent — in the opposite direction.
     *
     * <p>409 when the retry produces nothing. The requirement stays untested and its coverage row stays
     * PENDING, which is the honest outcome; a 200 would imply a recovery that did not happen.
     */
    @PostMapping("/{comparisonId}/requirements/{requirementId}/retry")
    public ResponseEntity<Finding> retryRequirement(@PathVariable String comparisonId,
                                                    @PathVariable String requirementId) {
        var job = jobs.findFirstByComparisonIdOrderByClaimedAtDesc(comparisonId).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        var retried = comparisonRunner.retryRequirement(job, requirementId);
        if (retried.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        comparisonRunner.rebuildCoverage(job);
        return ResponseEntity.ok(retried.get());
    }

    /**
     * The coverage ledger for one comparison. Architecture §17.6, §18.4.
     *
     * <p>Pulled alongside the findings on callback, for the same reason: §21 keeps job payloads to
     * references, and a ledger over a large standard is one entry per material requirement.
     *
     * <p>404 when no ledger was stored, which is the honest answer for a comparison run before ledgers
     * were persisted. An empty ledger would instead assert that nothing was expected.
     */
    @GetMapping("/{comparisonId}/coverage")
    public ResponseEntity<CoverageLedger> coverage(@PathVariable String comparisonId) {
        var ledger = resultWriter.readLedger(comparisonId);
        if (ledger == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ledger);
    }
}
