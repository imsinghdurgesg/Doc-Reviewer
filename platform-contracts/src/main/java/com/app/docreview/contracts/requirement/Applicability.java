package com.app.docreview.contracts.requirement;

import com.app.docreview.contracts.enums.ConditionRole;

import java.util.ArrayList;
import java.util.List;

/**
 * When a requirement applies. Architecture §12.5.
 *
 * <p>Lets a requirement be excluded from a comparison on structural grounds rather than being evaluated
 * and reported as MISSING. A change-management control has nothing to say about a data-retention
 * procedure, and reporting it as a gap there is noise.
 *
 * <p><strong>Two shapes, one meaning.</strong> {@code documentTypes} and {@code conditions} are the
 * original V2 fields — a type list and bare strings. {@code structuredConditions} is the V3 form, where
 * each condition carries its {@link ConditionRole} and the span it came from.
 *
 * <p>Both are retained because requirement sets are immutable once extracted (§12.1) and V2 sets are
 * still live: a comparison replaying one must read exactly what was stored. {@link #effectiveConditions()}
 * normalises either shape, so callers never branch on which extraction produced the data — and a V2
 * condition surfaces as {@link ConditionRole#UNKNOWN}, which is honest. Its role was never determined,
 * and UNKNOWN cannot exclude.
 */
public record Applicability(
        List<String> documentTypes,
        List<String> conditions,
        /**
         * V3 conditions with roles and evidence. Empty for V1 and V2 requirement sets.
         *
         * <p>When present, this is authoritative and {@code conditions} is its text projection — kept in
         * sync by the extraction path so an older reader still sees the condition text.
         */
        List<RequirementCondition> structuredConditions
) {

    public Applicability {
        // nonBlank rather than List.copyOf: copyOf REJECTS null elements with an NPE, and a model
        // response legitimately contains them — `documentTypes: [null, "Procedure"]` is a real shape.
        // Throwing in the constructor would fail the whole extraction over one null entry.
        documentTypes = nonBlank(documentTypes);
        conditions = nonBlank(conditions);
        structuredConditions = structuredConditions == null
                ? List.of()
                : structuredConditions.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** The V2 shape, for stored sets and callers that have no roles to supply. */
    public Applicability(List<String> documentTypes, List<String> conditions) {
        this(documentTypes, conditions, List.of());
    }

    /**
     * Every condition in structured form, whatever the extraction version.
     *
     * <p>V3 data returns as stored. V2 data is lifted into {@link ConditionRole#UNKNOWN} conditions with
     * no source spans — which reflects reality rather than papering over it: nothing recorded what those
     * conditions meant or where they came from, so they resolve to UNCERTAIN and are still compared.
     *
     * <p>A {@code documentTypes} list with no structured equivalent becomes a single
     * {@link ConditionRole#SCOPE_GATE}. That is the one case where inferring a role is safe: a
     * requirement limited to named document kinds is a scope gate by construction, and the V2 resolver
     * already treated it as one.
     */
    public List<RequirementCondition> effectiveConditions() {
        if (!structuredConditions.isEmpty()) {
            return structuredConditions;
        }
        // Already blank-stripped by the constructor: an extraction that produced `conditions: [""]`
        // stated nothing, and lifting that into a condition would push an ordinary requirement onto
        // the applicability-review path.
        var types = documentTypes;
        var texts = conditions;

        var lifted = new ArrayList<RequirementCondition>();
        // A universal scope ("all", "any", "*") states a limit syntactically while imposing none, so it
        // is not lifted into a condition at all. Recording it would make the resolver compare "all"
        // against a document type and find no match — excluding everything.
        if (!types.isEmpty() && !isUniversal(types)) {
            // EMPTY text, deliberately. A V2 documentTypes list has no prose to quote, and synthesising
            // "Limited to Runbook" would put a platform-authored paraphrase where the reviewer expects
            // the standard's own words. The types themselves carry the meaning, and the resolver's
            // reason string names them.
            lifted.add(new RequirementCondition(
                    "", ConditionRole.SCOPE_GATE, types, List.of()));
        }
        for (String condition : texts) {
            // UNKNOWN, not SCOPE_GATE. A V2 condition's role was never determined, and defaulting to
            // the one role that can exclude would retroactively give every legacy condition the power
            // to remove a requirement from a review.
            lifted.add(new RequirementCondition(
                    condition, ConditionRole.UNKNOWN, List.of(), List.of()));
        }
        return List.copyOf(lifted);
    }

    /** Null-safe on the LIST as well as its elements: both arrive from model output. */
    private static List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
    }

    /** Tokens that name a limit while imposing none. Kept in sync with the resolver's own set. */
    private static boolean isUniversal(List<String> documentTypes) {
        var universal = java.util.Set.of(
                "all", "any", "all documents", "any document", "*", "all process documents");
        return documentTypes.stream()
                .allMatch(type -> universal.contains(type.toLowerCase(java.util.Locale.ROOT)));
    }

    /** True when nothing was stated — the common case, and the applicability fast path. */
    public boolean isEmpty() {
        return effectiveConditions().isEmpty();
    }
}
