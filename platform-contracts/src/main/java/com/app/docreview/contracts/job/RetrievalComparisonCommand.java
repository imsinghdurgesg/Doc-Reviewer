package com.app.docreview.contracts.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Dispatches evidence retrieval and requirement comparison for one comparison. Architecture §16, §20.2.
 *
 * <p>{@code standardVersionIds} may be empty, which means "select standards automatically" via
 * document-level retrieval (§15.1). A non-empty list means the user chose explicitly and retrieval
 * must not substitute its own selection — an automatic pick silently overriding a user's choice
 * would make the resulting comparison misrepresent what was asked for.
 *
 * <p>No requirement text, no document text, no spans: the worker loads those from its own stores by
 * ID (§20.3). Requests carry references so the record stays replayable and log-safe.
 */
public record RetrievalComparisonCommand(

        @NotNull @Valid JobEnvelope envelope,

        @NotBlank String comparisonId,

        /** The uploaded process document being evaluated. */
        @NotBlank String documentVersionId,

        /**
         * Standards to compare against. Empty means automatic document-level selection (§15.1).
         * Never null — an absent list and an empty list would otherwise mean the same thing by
         * accident rather than by contract.
         */
        @NotNull List<String> standardVersionIds,

        /**
         * Cap on automatically selected standards. Ignored when the list above is explicit.
         * Bounded because each standard multiplies the number of per-requirement model calls (§15.5).
         */
        int maxAutomaticStandards,

        /**
         * Reviewer assessment context, or null. Free text such as "covers hardware procurement for the
         * India office".
         *
         * <p>A retrieval-focus and ranking signal ONLY. It must never restrict which standards are
         * eligible: a scoping note is the reviewer's description of their document, and treating it as
         * a filter would let an imprecise phrase hide the standard that actually governs (§15.1).
         */
        String guidance,

        @NotBlank String callbackUrl) {

    public RetrievalComparisonCommand {
        // Defensive copy: the command is persisted and replayed, and a caller mutating the list
        // afterwards would change what a replayed job compares against.
        standardVersionIds = standardVersionIds == null ? List.of() : List.copyOf(standardVersionIds);
    }

    /** True when the user picked standards and retrieval must not substitute its own (§15.1). */
    public boolean hasExplicitStandards() {
        return !standardVersionIds.isEmpty();
    }
}
