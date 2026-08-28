package com.app.docreview.retrievalcomparison.llm;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.comparison.ComparisonModelRequest;

import java.util.List;

/** Port for requirement comparison. Architecture §9.5. */
public interface ComparisonModelGateway {

    Result compare(ComparisonModelRequest request);

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
