package com.app.docreview.retrievalcomparison.llm;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.comparison.ComparisonModelRequest;

import java.util.List;

/** Port for requirement comparison. Architecture §9.5. */
public interface ComparisonModelGateway {

    Result compare(ComparisonModelRequest request);

    /**
     * Re-asks with a TARGETED correction appended. Architecture §18.4.
     *
     * <p><strong>Why the retry is not simply repeated.</strong> §18.4's one retry re-sent an identical
     * request, which only helps when the first response was a transient malformation. For a
     * DETERMINISTIC validation failure the model returns the same answer — CHG-08-R2 produced the
     * identical invalid PARTIAL four times across three retries, two model calls each, and ended
     * unevaluated every time. Naming the specific defect is what makes the second call different from
     * the first.
     *
     * @param repairInstruction what was wrong and what shape to return instead; appended as reviewer
     *                          guidance so the protected instructions still win
     */
    default Result compare(ComparisonModelRequest request, String repairInstruction) {
        return compare(request);
    }

    /**
     * @param rawResponseValid false when the response could not be parsed at all, so §18.4's single
     *                         retry can distinguish a malformed response from a semantically invalid
     *                         one — they need the same retry but different diagnostics
     */
    record Result(
            ComparisonModelOutput output,
            String modelAlias,
            String promptVersion,
            String schemaVersion,
            int promptTokens,
            int completionTokens,
            long latencyMillis,
            boolean rawResponseValid,
            List<String> errors
    ) {}
}
