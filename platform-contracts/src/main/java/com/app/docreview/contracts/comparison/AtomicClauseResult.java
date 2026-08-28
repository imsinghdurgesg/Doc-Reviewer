package com.app.docreview.contracts.comparison;

import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;

import java.util.List;

/**
 * Verdict for one atomic clause. Architecture §18.5.
 *
 * <p>Stored alongside the parent result, not instead of it (§18.5: "store both"). A parent verdict of
 * PARTIAL tells a reviewer something is wrong; the clause results tell them WHICH element failed —
 * that approval exists but independence does not. Without the clause level, "partial" is a shrug.
 *
 * <h2>Clause-specific vs requirement-level fields (schema V1)</h2>
 *
 * <p>{@code clauseId}, {@code status}, and {@code gapType} ARE clause-specific: the model returns
 * satisfied/unsatisfied clause IDs and each verdict is derived from them.
 *
 * <p>{@code reason} and {@code processSpanIds} are NOT. The V1 model contract carries a single
 * {@code reason} and one span list for the whole requirement, so there is nothing clause-specific to
 * put here. They were previously populated by copying the parent's values onto every clause, which
 * made three clauses display the same paragraph — a paragraph that discussed all three, next to a
 * per-clause status chip. Worse, an ALIGNED clause claimed every span the requirement cited,
 * asserting evidence that had never been attributed to it.
 *
 * <p>They are therefore left EMPTY in V1 and the parent's reason and evidence are displayed once, at
 * requirement level. Genuine per-clause explanation and attribution require the model to produce
 * them, which is a versioned contract change (schema V2) rather than something this record can
 * synthesise.
 *
 * @param clauseText     readable rendering of the clause this verdict is about, from
 *                       {@code AtomicClause.displayText()}. Without it two sibling clauses render as
 *                       identical rows differing only by an ID suffix, so a reviewer cannot see WHICH
 *                       obligation failed — the one thing clause-level results exist to answer.
 *                       Null on results stored before the field existed.
 * @param processSpanIds clause-specific supporting evidence. Empty under schema V1 — the model did
 *                       not attribute evidence per clause, and inheriting the parent's list would
 *                       claim traceability that does not exist (§16.5).
 * @param reason         clause-specific explanation. Null under schema V1, for the same reason.
 */
public record AtomicClauseResult(
        String clauseId,
        String clauseText,
        ComparisonStatus status,
        GapType gapType,
        List<String> processSpanIds,
        String reason
) {

    /**
     * Legacy shape, without {@code clauseText}.
     *
     * <p>Retained so results persisted before the field existed deserialise, and so test fixtures
     * that predate it keep compiling. A result built this way carries no text and the UI falls back
     * to showing the clause ID alone — which is the very ambiguity the field was added to remove, so
     * new code should always supply it.
     */
    public AtomicClauseResult(String clauseId, ComparisonStatus status, GapType gapType,
                              List<String> processSpanIds, String reason) {
        this(clauseId, null, status, gapType, processSpanIds, reason);
    }
}
