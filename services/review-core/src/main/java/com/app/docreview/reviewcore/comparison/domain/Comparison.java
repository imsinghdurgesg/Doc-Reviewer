package com.app.docreview.reviewcore.comparison.domain;

import com.app.docreview.contracts.comparison.CoverageLedger;
import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.job.ComparisonState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A completed or in-progress review. Architecture §8.1, §19.2.
 *
 * <p><strong>This is the authoritative record</strong>, owned by review-core (§22.1).
 * retrieval-comparison holds working results only; §8.3 forbids it owning history or reviewer
 * decisions. That split is what lets a working set be recomputed while a completed comparison stays
 * exactly as measured.
 *
 * <p>The version fields exist to satisfy §19.2 and §39 rule 30: reopening a comparison must show the
 * saved result, never silently re-run against a newer standard, prompt, model, or index. Storing the
 * result without the versions that produced it would make it unreproducible — the finding would be
 * there but nothing would say what it was measured against.
 *
 * <p>{@code publicId} is the shareable identifier. Separate from the Mongo {@code id} so a URL a
 * reviewer pastes into a ticket does not expose a database key.
 */
@Document(collection = "comparisons")
@Getter
@NoArgsConstructor
public class Comparison {

    @Id
    private String id;

    @Indexed(unique = true)
    private String publicId;

    @Indexed
    private String documentVersionId;

    /** Standards evaluated. Empty until selection resolves (§15.1). */
    private List<String> standardVersionIds;

    @Setter private ComparisonState state;
    @Setter private String stateMessage;
    @Setter private Integer evaluatedRequirements;
    @Setter private Integer totalRequirements;

    /**
     * Validated findings, hydrated for display (§16.5).
     *
     * <p>Embedded rather than a separate collection: a comparison's findings are always read with the
     * comparison, are immutable once written, and are bounded by the per-comparison requirement cap
     * (§15.5). A join would buy nothing.
     */
    @Setter private List<Finding> findings;

    /**
     * Requirements that could not be evaluated, as {@code requirementId: reason}. Architecture §18.4.
     *
     * <p>Persisted rather than only counted. The worker already reports which controls failed and
     * why, but the comparison stored just the total — so a reviewer was told "1 of 15 requirements
     * could not be evaluated" with no way to learn WHICH one. That turns a bounded, inspectable gap
     * into an unbounded doubt about the whole review: the one unevaluated control might be the one
     * that mattered.
     *
     * <p>Reasons are diagnostics (an exception type or a validation problem), never document text —
     * the worker keeps them display-safe for exactly this reason (§10.5, §34).
     */
    @Setter private List<String> unevaluatedRequirements;

    // ---- §19.2 reproducibility snapshot -------------------------------------------------------
    @Setter private String comparisonModelAlias;
    @Setter private String comparisonPromptVersion;

    /**
     * Administrator guidance in force when this comparison ran. Architecture §19.2.
     *
     * <p>The version's digest identifies a change; this explains it. Without the text, reopening a
     * finding months later tells a reader that the instructions differed from another run but not what
     * they said — which is the difference between noticing a discrepancy and being able to defend a
     * conclusion.
     *
     * <p>Null for comparisons run with no guidance, which is most of them.
     */
    @Setter private String comparisonGuidance;
    @Setter private String comparisonSchemaVersion;
    @Setter private String requirementSetVersion;

    /**
     * Which requirements were covered, and which were not. Architecture §17.6, §19.2.
     *
     * <p>Stored on the comparison rather than read from the worker on demand, so it becomes immutable
     * with the review it describes. The worker's copy is a working set that a re-run replaces; a
     * completed review must not have its coverage rewritten after the fact.
     *
     * <p>Null on comparisons completed before ledgers were persisted — meaningfully different from an
     * empty ledger, which would claim no requirements were expected.
     */
    @Setter private CoverageLedger coverageLedger;

    /**
     * Which coverage schema this comparison was created under. Architecture §17.6, §19.2.
     *
     * <p><strong>Why a stamp and not a date.</strong> It is the only way to tell an exempt comparison
     * from a broken one. The ledger's own absence cannot: a review that predates persistence and one
     * whose snapshot failed to copy both have no ledger, and they demand opposite responses — show
     * "not recorded" versus refuse to record a terminal state at all.
     *
     * <p>Null on the comparisons that ran before this existed. They stay null: an invariant written
     * afterwards must not retroactively invalidate completed reviews (§19.2 immutability).
     */
    @Setter private String coverageSchemaVersion;

    @Setter private String embeddingModelAlias;
    @Setter private Integer embeddingDimensions;
    @Setter private String indexVersion;
    @Setter private String chunkingStrategyVersion;
    @Setter private String retrievalStrategyVersion;

    /**
     * Who ran the review. Displayed in history and matched by its search.
     *
     * <p>Indexed for the equality lookups this field otherwise attracts ("this person's reviews").
     * Note the history search is a case-insensitive {@code $regex}, which cannot use this index —
     * that query is already a scan because it also matches {@code publicId} the same way, so the
     * index neither helps nor hurts there.
     */
    @Indexed
    private String createdBy;
    private Instant createdAt;
    @Setter private Instant completedAt;

    public Comparison(String publicId, String documentVersionId,
                      List<String> standardVersionIds, String createdBy) {
        this.publicId = publicId;
        this.documentVersionId = documentVersionId;
        this.standardVersionIds = standardVersionIds == null
                ? new ArrayList<>() : new ArrayList<>(standardVersionIds);
        this.createdBy = createdBy;
        this.state = ComparisonState.UPLOADED;
        this.findings = new ArrayList<>();
        this.createdAt = Instant.now();
        // Stamped at CREATION, not on completion. The invariant has to be decidable from the record
        // itself: stamping it later would make an exempt comparison and a broken one identical.
        this.coverageSchemaVersion = COVERAGE_SCHEMA_V1;
    }

    /** Comparisons created from here on must carry a coverage snapshot to reach a terminal state. */
    public static final String COVERAGE_SCHEMA_V1 = "COVERAGE-V1";

    public void setStandardVersionIds(List<String> ids) {
        this.standardVersionIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    /**
     * True once the result is fixed and must not be recomputed (§19.2).
     *
     * <p>PARTIALLY_COMPLETED counts as terminal: §18.4 makes it a real outcome with real findings,
     * not an intermediate state to retry.
     */
    public boolean terminal() {
        return state == ComparisonState.COMPLETED
                || state == ComparisonState.PARTIALLY_COMPLETED
                || state == ComparisonState.FAILED;
    }
}
