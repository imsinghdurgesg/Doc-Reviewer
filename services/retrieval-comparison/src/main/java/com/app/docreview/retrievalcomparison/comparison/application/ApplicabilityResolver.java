package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.enums.ApplicabilityStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.requirement.Applicability;
import com.app.docreview.contracts.requirement.RequirementCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a requirement applies to a document. Architecture §12.5, §17.6.
 *
 * <p><strong>No model call, per requirement or otherwise.</strong> A comparison already spends one model
 * call per requirement on the compliance verdict; adding a second to ask "does this even apply?" would
 * double the cost and latency of every review to answer a question that is usually not in doubt. The
 * decision here is made from data already extracted — the requirement's stated
 * {@link Applicability} and the document's own type.
 *
 * <p><strong>The default is APPLICABLE, and it is reached without any analysis.</strong> A requirement
 * with no stated condition applies, full stop. That is the overwhelming majority: a standard governing
 * change management governs all of it, and only a minority of clauses carry "this applies only to
 * cloud-hosted systems". Returning early for the unconditional case is what keeps this free.
 *
 * <p><strong>SCOPE and APPLICABILITY chunks are consulted only for a GENUINE condition.</strong> They
 * are retrieval context for resolving a stated condition, not a reason to re-litigate applicability for
 * every requirement. Fetching them unconditionally would add a vector search per requirement and invite
 * the classifier to invent conditions the standard never wrote.
 *
 * <p><strong>Doubt does not exclude.</strong> An unresolvable condition yields
 * {@link ApplicabilityStatus#UNCERTAIN}, which is still compared. Excluding on doubt would
 * let an ambiguous phrase silently remove a real obligation, and the reviewer would see a clean result
 * with nothing indicating a requirement had been dropped.
 */
@Service
@Slf4j
public class ApplicabilityResolver {

    /**
     * Document-type tokens treated as universal.
     *
     * <p>A requirement scoped to "all documents" states a condition syntactically while imposing none,
     * and matching it literally against a document type would exclude everything.
     */
    private static final Set<String> UNIVERSAL = Set.of(
            "all", "any", "all documents", "any document", "*", "all process documents");

    /**
     * Resolves using GOVERNED effective conditions. Architecture §12.5, §19.2.
     *
     * <p>The authoritative path once conditions have been reviewed. Differs from the extraction-only
     * overload in one decisive way: a condition may exclude only when its effective role is a scope gate
     * AND a confirmed override authorises exclusion. An unreviewed prose scope gate therefore cannot
     * remove a requirement from a review — which matters because a review of 115 such model predictions
     * reclassified 113.
     *
     * @param effective the conditions with review decisions applied, from
     *                  {@link EffectiveConditionResolver}
     */
    public Decision resolveGoverned(List<EffectiveConditionResolver.EffectiveCondition> effective,
                                    ConfirmedContext context) {
        var ctx = context == null ? ConfirmedContext.empty() : context;

        if (effective == null || effective.isEmpty()) {
            return new Decision(ApplicabilityStatus.APPLICABLE, null, false,
                    List.of(), List.of(), List.of());
        }

        var conditionSpans = effective.stream()
                .flatMap(e -> e.condition().conditionSourceSpanIds().stream())
                .distinct()
                .toList();
        // Every stated condition, verbatim, on every outcome.
        //
        // Deliberately NOT filtered by role. Whatever the decision turns out to be, the reviewer needs
        // to read what the standard actually said — and a role-filtered list would drop an unauthorised
        // scope gate entirely, so a requirement conditioned on "all production changes" would be
        // compared as though unconditional with nothing to indicate the qualifier had been discarded.
        var allText = effective.stream()
                .map(e -> e.condition().text())
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();

        // Only conditions that MAY exclude are treated as gates. Everything else — including a scope
        // gate whose exclusion is unauthorised — informs the comparison instead.
        var gates = effective.stream()
                .filter(EffectiveConditionResolver.EffectiveCondition::mayExclude)
                .toList();

        for (var gate : gates) {
            var types = clean(gate.condition().documentTypes());
            if (types.isEmpty() || isUniversal(types)) {
                // Authorised but not machine-decidable. Still not guessed — UNCERTAIN with evidence.
                return new Decision(ApplicabilityStatus.UNCERTAIN,
                        "Requirement states a scope condition that could not be evaluated "
                                + "automatically: " + gate.condition().text(),
                        true, List.of(), allText, conditionSpans);
            }
            if (!ctx.hasDocumentType()) {
                return new Decision(ApplicabilityStatus.UNCERTAIN,
                        "Requirement is scoped to " + types
                                + " but this document's type is not confirmed",
                        true, List.of("documentType"), allText, conditionSpans);
            }
            boolean matches = types.stream()
                    .anyMatch(type -> type.equalsIgnoreCase(ctx.documentType()));
            if (!matches) {
                // Exclusion: a scope gate, confirmed by a human, authorised to exclude, and in clear
                // conflict with confirmed context. All four conditions, deliberately.
                return new Decision(ApplicabilityStatus.NOT_APPLICABLE,
                        "Requirement applies to " + types
                                + "; this document is confirmed as " + ctx.documentType(),
                        true, List.of(), allText, conditionSpans);
            }
        }

        // An UNRESOLVED condition means a reviewer looked and could not settle it, so the requirement is
        // compared and flagged rather than silently treated as plainly applicable.
        boolean anyUnresolved = effective.stream()
                .anyMatch(e -> e.effectiveRole() == ConditionRole.UNKNOWN);
        if (anyUnresolved) {
            return new Decision(ApplicabilityStatus.UNCERTAIN,
                    "Requirement states a condition whose applicability role is unresolved: "
                            + summarise(allText),
                    true, List.of(), allText, conditionSpans);
        }

        // Reaching here means NO condition excluded anything, so all of them inform the comparison —
        // including a gate that matched the document type, whose text is what shows WHY the requirement
        // was held applicable.
        return new Decision(ApplicabilityStatus.APPLICABLE, null, false,
                List.of(), allText, conditionSpans);
    }

    /**
     * Resolves applicability from the EXTRACTION alone, with no review decisions applied.
     *
     * <p>Used where no override set exists — a freshly extracted standard, or a test fixture. Prefer
     * {@link #resolveGoverned} once conditions have been reviewed: this overload trusts the model's own
     * role classification, and a review of 115 such predictions reclassified 113 of them.
     *
     * <p><strong>Confirmed matters.</strong> An inferred document type is a guess the reviewer has not
     * endorsed; excluding a requirement on one would let a model's mid-confidence label remove a real
     * obligation. So an unconfirmed value resolves to {@link ApplicabilityStatus#UNCERTAIN} rather than
     * {@code NOT_APPLICABLE} — the requirement is still compared, and the reviewer is told what to
     * confirm.
     */
    public Decision resolve(Applicability applicability, ConfirmedContext context) {
        var ctx = context == null ? ConfirmedContext.empty() : context;

        // THE FAST PATH, and the common one. No condition means it applies, with no work done.
        if (applicability == null || applicability.isEmpty()) {
            return new Decision(ApplicabilityStatus.APPLICABLE, null, false,
                    List.of(), List.of(), List.of());
        }

        var all = applicability.effectiveConditions();

        // ROLE DECIDES THE PATH.
        //
        // Previously every condition took the same route, so a corpus where 116 of 254 requirements
        // carried a condition put nearly half the standard on the applicability-review path — and all
        // of them within reach of NOT_APPLICABLE. But "when technically feasible" changes HOW a
        // requirement is met, and "within one business day" is part of the obligation. Neither is a
        // question of scope.
        //
        // Only SCOPE_GATE can exclude. Every other role means the requirement APPLIES and its
        // condition belongs in the comparison, so the model judges it as the standard words it.
        var scopeGates = all.stream().filter(RequirementCondition::canExclude).toList();
        // NOTE: this overload uses the EXTRACTED role, so it is the unreviewed path. The governed path
        // is resolve(List<EffectiveCondition>, ...) below, which additionally requires exclusion to be
        // authorised by a confirmed override. Kept because unreviewed corpora are the normal case.
        var comparisonConditions = all.stream()
                .filter(RequirementCondition::informsComparison)
                .toList();

        // Evidence is the condition's OWN source span, always available — no chunk reprocessing
        // needed. Previously an UNCERTAIN decision could arrive with zero spans because the corpus had
        // no SCOPE-typed chunks, leaving the reviewer a conclusion and nothing to check it against.
        var conditionSpans = all.stream()
                .flatMap(c -> c.conditionSourceSpanIds().stream())
                .distinct()
                .toList();

        if (scopeGates.isEmpty()) {
            // No scope gate. Any UNKNOWN role still means "we could not tell", so it is UNCERTAIN and
            // compared; a definite non-scope role means the requirement plainly applies.
            boolean anyUnknown = all.stream()
                    .anyMatch(c -> c.role() == com.app.docreview.contracts.enums.ConditionRole.UNKNOWN);
            if (anyUnknown) {
                return new Decision(ApplicabilityStatus.UNCERTAIN,
                        "Requirement states a condition whose role could not be classified: "
                                + summariseConditions(all),
                        true, List.of(), textOf(all), conditionSpans);
            }
            return new Decision(ApplicabilityStatus.APPLICABLE, null, false,
                    List.of(), textOf(comparisonConditions), conditionSpans);
        }

        // At least one scope gate. Resolve it against CONFIRMED context only.
        for (var gate : scopeGates) {
            var types = clean(gate.documentTypes());
            if (types.isEmpty() || isUniversal(types)) {
                // A prose scope gate with no machine-decidable type list, or one scoped to "all".
                // Not resolvable here and NOT sent to a model in this phase — UNCERTAIN with the
                // condition's own evidence attached.
                return new Decision(ApplicabilityStatus.UNCERTAIN,
                        "Requirement states a scope condition that could not be evaluated "
                                + "automatically: " + gate.text(),
                        true, List.of(), textOf(all), conditionSpans);
            }
            if (!ctx.hasDocumentType()) {
                // ABSENCE IS NOT EXCLUSION EVIDENCE. The requirement is scoped to specific document
                // kinds and the confirmed context does not say which this is — so we do not know, and
                // excluding would drop the requirement because METADATA is missing rather than because
                // the standard says so.
                return new Decision(ApplicabilityStatus.UNCERTAIN,
                        "Requirement is scoped to " + types
                                + " but this document's type is not confirmed",
                        true, List.of("documentType"), textOf(all), conditionSpans);
            }
            boolean matches = types.stream()
                    .anyMatch(type -> type.equalsIgnoreCase(ctx.documentType()));
            if (!matches) {
                // THE ONLY ROUTE TO EXCLUSION: a SCOPE_GATE clearly conflicting with a value a human
                // confirmed. Affirmative evidence, not absence.
                return new Decision(ApplicabilityStatus.NOT_APPLICABLE,
                        "Requirement applies to " + types
                                + "; this document is confirmed as " + ctx.documentType(),
                        true, List.of(), textOf(all), conditionSpans);
            }
        }

        // Every scope gate matched — but a matched gate does not resolve a SEPARATE unclassified
        // condition. "Applies to procedures, only where personal data is processed" has answered the
        // first clause and not the second, and reporting APPLICABLE would assert the platform had
        // judged both.
        boolean anyUnknown = all.stream()
                .anyMatch(c -> c.role() == com.app.docreview.contracts.enums.ConditionRole.UNKNOWN);
        if (anyUnknown) {
            return new Decision(ApplicabilityStatus.UNCERTAIN,
                    "Requirement states a further condition whose role could not be classified: "
                            + summariseConditions(all),
                    true, List.of(), textOf(all), conditionSpans);
        }

        // Scope satisfied and every remaining condition classified. Non-scope conditions travel to the
        // comparison so the model judges the requirement as the standard words it.
        return new Decision(ApplicabilityStatus.APPLICABLE, null, false,
                List.of(), textOf(comparisonConditions), conditionSpans);
    }

    /**
     * Condition text for the reviewer, blanks omitted.
     *
     * <p>A lifted V2 {@code documentTypes} gate has no prose to quote, and surfacing it as an empty
     * string would put a meaningless entry in a reviewer-facing list. The types are named in the
     * reason string instead.
     */
    private static List<String> textOf(List<RequirementCondition> conditions) {
        return conditions.stream()
                .map(RequirementCondition::text)
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }

    /** First condition text plus a count, for a reviewer-facing reason. */
    private static String summariseConditions(List<RequirementCondition> conditions) {
        return summarise(textOf(conditions));
    }

    /**
     * Assessment context the REVIEWER has confirmed. Architecture §15.1.
     *
     * <p>Deliberately not the same type as the inference-time {@code AssessmentContext}: that one holds
     * values the platform guessed and uses them as soft ranking signals, where being wrong costs
     * ranking quality. This one can EXCLUDE a requirement from a review, so it must only ever hold
     * values a human endorsed.
     *
     * @param documentType the confirmed document type, or null
     */
    public record ConfirmedContext(String documentType) {

        public ConfirmedContext {
            documentType = documentType == null || documentType.isBlank() ? null : documentType.trim();
        }

        public static ConfirmedContext empty() {
            return new ConfirmedContext(null);
        }

        /**
         * Treats a bare document type as confirmed.
         *
         * <p>For the existing single-argument {@code resolve} overload and for callers that have
         * already established the value is reviewer-supplied. New callers should construct the record
         * directly so the confirmation status is explicit at the call site.
         */
        public static ConfirmedContext ofDocumentType(String documentType) {
            return new ConfirmedContext(documentType);
        }

        public boolean hasDocumentType() {
            return documentType != null;
        }
    }


    private static boolean isUniversal(List<String> documentTypes) {
        return documentTypes.stream()
                .allMatch(type -> UNIVERSAL.contains(type.toLowerCase(Locale.ROOT)));
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
    }

    /** First condition plus a count. The full list is on the requirement; this is for a reason string. */
    private static String summarise(List<String> conditions) {
        String first = conditions.getFirst();
        String trimmed = first.length() > 120 ? first.substring(0, 120) + "…" : first;
        return conditions.size() == 1
                ? trimmed
                : trimmed + " (and " + (conditions.size() - 1) + " more)";
    }

    /**
     * @param status        the resolution
     * @param reason        why, for anything other than APPLICABLE. Null when the requirement simply
     *                      applies — a reason for the default case is noise on every finding.
     * @param needsScopeContext true when SCOPE and APPLICABILITY chunks would help a human or a later
     *                      phase resolve the condition. Only ever set for a requirement that genuinely
     *                      states one, which bounds how often that retrieval happens.
     */
    public record Decision(
            ApplicabilityStatus status,
            String reason,
            boolean needsScopeContext,
            /**
             * Confirmed-context fields that would have resolved this, e.g. {@code ["documentType"]}.
             *
             * <p>Empty unless UNCERTAIN, and empty even then when no field would help — a free-text
             * condition is not resolved by supplying metadata, so sending the reviewer to fill in a
             * form would waste their time and imply the platform would then decide.
             */
            List<String> missingContextFields,
            /** The stated conditions, verbatim, so the reviewer reads the standard not a paraphrase. */
            List<String> conditions,
            /**
             * Standard spans the conditions were extracted FROM. Architecture §16.5.
             *
             * <p>The immediate evidence for an applicability decision, available without any chunk
             * reprocessing. Dedicated SCOPE and APPLICABILITY passages are additional context the
             * coordinator adds when they exist — a condition embedded in the requirement legitimately
             * has only its own source span.
             */
            List<String> conditionSourceSpanIds) {

        public Decision {
            missingContextFields = missingContextFields == null
                    ? List.of() : List.copyOf(missingContextFields);
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            conditionSourceSpanIds = conditionSourceSpanIds == null
                    ? List.of() : List.copyOf(conditionSourceSpanIds);
        }

        public boolean shouldCompare() {
            return status.shouldCompare();
        }
    }

    /**
     * Identifies the rule set that produced a decision. Architecture §19.2.
     *
     * <p>Persisted with every decision so a rule change cannot silently reinterpret an existing review.
     * Bump on ANY change to the logic above.
     */
    public static final String RESOLVER_VERSION = "APPLICABILITY-RULES-V3";
}
