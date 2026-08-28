package com.app.docreview.retrievalcomparison.jobs;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Persists atomic comparison results. Architecture §22.3, §18.6.
 *
 * <p>These are the worker's WORKING results, not the authoritative history. §8.3 forbids this service
 * from owning final comparisons or reviewer decisions, and §22 forbids it writing review-core's
 * collections — so results land in {@code atomic_comparison_results} here, and review-core persists
 * the authoritative comparison when it processes the callback.
 *
 * <p>That split looks redundant until a re-run happens: the working set can be rewritten freely,
 * while the completed comparison review-core holds must stay exactly as it was measured (§19.2).
 *
 * <p>Writes are idempotent by comparison ID — a recovered job replaces its own prior results rather
 * than appending a second set (§21, §39 rule 23).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComparisonResultWriter {

    static final String RESULTS_COLLECTION = "atomic_comparison_results";

    /** Separate collection: one ledger per comparison, versus one document per finding. */
    static final String LEDGER_COLLECTION = "comparison_coverage_ledgers";

    private final MongoTemplate mongo;

    public void write(String comparisonId, String documentVersionId, List<Finding> findings) {
        // Replace rather than append. See the class comment on idempotency.
        mongo.remove(Query.query(Criteria.where("comparisonId").is(comparisonId)),
                RESULTS_COLLECTION);

        if (findings.isEmpty()) {
            // Not an error. A document that satisfies every material requirement legitimately has no
            // findings, and §37's demo journey includes that state.
            log.info("Comparison {} produced no findings", comparisonId);
            return;
        }

        mongo.insert(findings, RESULTS_COLLECTION);
        log.info("Stored {} finding(s) for comparison {}", findings.size(), comparisonId);
    }

    /**
     * Adds or replaces ONE finding, for a targeted retry. Architecture §18.4.
     *
     * <p>Replace-by-requirement rather than insert. A retry that appended would leave two rows for one
     * requirement, and the consolidation step upstream is not re-run here — so a reviewer would see the
     * same control twice with different verdicts and no way to tell which is current.
     *
     * <p>Scoped to the comparison AND the requirement, never a broader filter: this runs against a
     * collection holding every comparison's working results.
     */
    public void appendFinding(Finding finding) {
        mongo.remove(Query.query(Criteria.where("comparisonId").is(finding.comparisonId())
                        .and("requirementId").is(finding.requirementId())),
                RESULTS_COLLECTION);
        mongo.insert(finding, RESULTS_COLLECTION);
        log.info("Stored retried finding for {} in comparison {}",
                finding.requirementId(), finding.comparisonId());
    }

    /** Reads back the working results, for review-core's hydration call (§16.5). */
    public List<Finding> read(String comparisonId) {
        return mongo.find(Query.query(Criteria.where("comparisonId").is(comparisonId)),
                Finding.class, RESULTS_COLLECTION);
    }

    /**
     * Persists the coverage ledger. Architecture §17.6, §18.4.
     *
     * <p><strong>Why it must be stored, not just computed.</strong> The ledger already decides
     * COMPLETED vs PARTIALLY_COMPLETED, so the platform knows exactly which requirements went
     * untested — and then discarded it. A reviewer saw "partially completed" with no way to learn
     * WHICH controls were missing, which is the one thing that makes the warning actionable.
     *
     * <p>Same replace-not-append idempotency as findings: a re-run must not leave two ledgers whose
     * counts disagree.
     */
    public void writeLedger(CoverageLedger ledger) {
        mongo.remove(Query.query(Criteria.where("comparisonId").is(ledger.comparisonId())),
                LEDGER_COLLECTION);
        mongo.insert(ledger, LEDGER_COLLECTION);
        // Counts only, never requirement text or condition text (§35).
        log.info("Stored coverage ledger for {}: {} entries, {} untested",
                ledger.comparisonId(), ledger.expectedCount(), ledger.untestedCount());
    }

    /**
     * Reads the ledger back, or null when none was stored.
     *
     * <p>Null rather than an empty ledger, deliberately: an empty ledger asserts "nothing was
     * expected", which would report a comparison run before ledgers existed as having zero coverage
     * rather than unknown coverage.
     */
    public CoverageLedger readLedger(String comparisonId) {
        return mongo.findOne(Query.query(Criteria.where("comparisonId").is(comparisonId)),
                CoverageLedger.class, LEDGER_COLLECTION);
    }
}
