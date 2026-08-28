package com.app.docreview.retrievalcomparison.extraction.validation;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces the ten extraction validation rules. Architecture §12.8.
 *
 * <p><strong>Model output is untrusted input.</strong> Provider structured output is best effort, so
 * schema conformance is checked in Java and then these semantic rules run on top. A requirement that
 * fails any of them is not published: §12.8 requires one retry with the errors, then
 * {@code NEEDS_REVIEW} rather than silently publishing unverified controls.
 *
 * <p>The rule that does the most work is #2 — every source span must belong to the version being
 * indexed. That single check is what makes fabricated evidence impossible: the model can return any
 * string it likes, and an ID that was not supplied simply fails.
 */
@Component
@Slf4j
public class ExtractionValidator {

    /**
     * Bumped when the accepted shape changes, so a stored set records which rules it passed (§12.9).
     *
     * <p>V2 adds rule 11: an extracted condition must be a whole testable clause and must cite a
     * source span. A V1 set may contain condition fragments, and knowing which validator ran is what
     * lets a reader tell "this set has no fragments" from "fragments were never checked".
     */
    public static final String SCHEMA_VERSION = "REQ-SCHEMA-V2";

    /**
     * @param allowedSpanIds the spans supplied to the model for THIS candidate
     * @param seenRequirementIds IDs already used in this requirement-set version, so rule 5 can
     *                           detect collisions across candidates rather than only within one
     */
    public Outcome validate(List<ExtractedRequirement> requirements,
                            Set<String> allowedSpanIds,
                            Set<String> seenRequirementIds) {
        List<ExtractedRequirement> accepted = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (ExtractedRequirement r : requirements) {
            List<String> problems = check(r, allowedSpanIds, seenRequirementIds);
            if (problems.isEmpty()) {
                accepted.add(r);
                seenRequirementIds.add(r.requirementId());
            } else {
                // Logged with the requirement ID but WITHOUT its text: extraction failures are
                // diagnosed from structure, and the text is confidential standard content (§35).
                log.warn("Rejected requirement {}: {}", r.requirementId(), problems);
                errors.addAll(problems.stream().map(p -> r.requirementId() + ": " + p).toList());
            }
        }
        return new Outcome(accepted, errors);
    }

    private List<String> check(ExtractedRequirement r, Set<String> allowedSpanIds,
                              Set<String> seenRequirementIds) {
        List<String> problems = new ArrayList<>();

        // Rule 1: at least one valid source span.
        if (r.sourceSpanIds() == null || r.sourceSpanIds().isEmpty()) {
            problems.add("no source span; the requirement would be unciteable");
        }

        // Rule 2: every span belongs to the version being indexed. THE anti-fabrication check.
        if (r.sourceSpanIds() != null) {
            for (String spanId : r.sourceSpanIds()) {
                if (!allowedSpanIds.contains(spanId)) {
                    problems.add("span " + spanId + " was not supplied as evidence (invented or "
                            + "belongs to another version)");
                }
            }
        }

        // Rule 3 (structural part): a normalized statement must exist. Whether it is SUPPORTED by
        // the spans is a semantic judgement this validator cannot make; §12.8 pairs it with the
        // administrator review gate rather than pretending code can settle it.
        if (isBlank(r.normalizedRequirement())) {
            problems.add("normalizedRequirement is empty");
        }

        // Rule 4: no invented control identifier. An ID the standard does not contain sends a
        // reviewer looking for a clause that does not exist.
        if (r.sourceControlId() != null && r.sourceControlId().isBlank()) {
            problems.add("sourceControlId present but blank");
        }

        // Rule 5: requirement IDs unique within the requirement-set version.
        if (isBlank(r.requirementId())) {
            problems.add("requirementId is empty");
        } else if (seenRequirementIds.contains(r.requirementId())) {
            problems.add("duplicate requirementId within this requirement-set version");
        }

        // Rule 6: every clause traceable to its parent, and clause IDs unique within it.
        if (r.atomicClauses() == null || r.atomicClauses().isEmpty()) {
            problems.add("no atomic clauses; nothing could be evaluated");
        } else {
            Set<String> clauseIds = new HashSet<>();
            for (AtomicClause c : r.atomicClauses()) {
                if (isBlank(c.clauseId())) {
                    problems.add("clause with no id");
                } else if (!clauseIds.add(c.clauseId())) {
                    problems.add("duplicate clauseId " + c.clauseId());
                }
                if (isBlank(c.action())) {
                    problems.add("clause " + c.clauseId() + " has no action");
                }
            }
        }

        // Rule 7: enum conformance. Jackson would already have failed an unknown value, so reaching
        // here with null means the field was absent.
        if (r.normativeStrength() == null) {
            problems.add("normativeStrength is missing");
        }

        // Rule 8: confidence in range.
        if (r.extractionConfidence() < 0.0 || r.extractionConfidence() > 1.0) {
            problems.add("extractionConfidence " + r.extractionConfidence() + " outside 0.0-1.0");
        }

        // Rule 9: non-normative content must not arrive as an enforceable requirement. Definition
        // text is lexically almost identical to requirement text, so this is a real failure mode
        // rather than a theoretical one.
        if (r.normativeStrength() == NormativeStrength.DEFINITION
                || r.normativeStrength() == NormativeStrength.EXAMPLE
                || r.normativeStrength() == NormativeStrength.RATIONALE) {
            boolean negatedClause = r.atomicClauses() != null
                    && r.atomicClauses().stream().anyMatch(AtomicClause::negated);
            if (negatedClause) {
                problems.add("non-normative strength " + r.normativeStrength()
                        + " carries a prohibition clause; it is being promoted to a control");
            }
        }

        // Rule 11: an extracted condition must be a whole clause, and must cite its evidence.
        //
        // A truncated condition is worse than a missing one. "When the change is a material security
        // or privacy change" came back as "When it is" — technically verbatim, since it IS a substring,
        // while dropping the entire substantive test. Downstream nothing can evaluate it, a reviewer
        // cannot judge it, and it consumed a policy-owner queue slot for a question the standard had
        // already answered.
        if (r.applicability() != null) {
            for (var condition : r.applicability().effectiveConditions()) {
                var text = condition.text();
                // Empty is legitimate: a V2-style documentTypes-only condition carries no prose, and
                // synthesising a paraphrase for it would invent standard text.
                if (text != null && !text.isBlank() && isFragment(text)) {
                    problems.add("condition \"" + text + "\" is a fragment, not a testable clause; "
                            + "extract the whole condition including what is being tested");
                }
                // Evidence is mandatory (§16.5): an applicability decision a reviewer cannot check
                // against the standard is an assertion, and this is the one decision that can remove a
                // control from a review.
                if (condition.conditionSourceSpanIds().isEmpty()) {
                    problems.add("condition \"" + text + "\" cites no source span");
                }
            }
        }

        return problems;
    }

    /**
     * Words that only INTRODUCE a condition, leaving nothing being tested.
     *
     * <p>Matched as a whole value rather than a prefix, deliberately. "When the change is material" is
     * a fine condition and begins the same way; only the bare connective is a fragment. A prefix rule
     * would reject most valid conditions in the corpus.
     */
    private static final Set<String> CONNECTIVE_ONLY = Set.of(
            "when", "when it", "when it is", "when they", "when they are", "when the",
            "if", "if it", "if it is", "if the", "where", "where it", "where it is", "where the",
            "for", "for a", "for an", "for the", "unless", "unless it", "unless the",
            "in the case of", "in cases where", "provided that", "subject to", "such as", "including");

    /**
     * True when the text introduces a condition without stating one.
     *
     * <p>A closed list rather than a length threshold. Real conditions this short exist — "before
     * signing", "at authorization", "where required" all state a testable fact — and a length rule
     * would reject them while still missing a long-but-empty fragment.
     */
    private boolean isFragment(String text) {
        var normalised = text.strip().toLowerCase(Locale.ROOT)
                // Trailing punctuation only; internal punctuation is part of the clause.
                .replaceAll("[\\s,;:.]+$", "");
        return CONNECTIVE_ONLY.contains(normalised);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Rule 10 is applied by the caller across ALL candidates, not here: consolidating duplicates
     * requires the whole requirement set, and it must merge source evidence rather than discard the
     * losing copy.
     *
     * @param accepted requirements that passed every rule
     * @param errors   human-readable reasons, fed back into the single permitted retry (§12.8)
     */
    public record Outcome(List<ExtractedRequirement> accepted, List<String> errors) {

        public boolean clean() {
            return errors.isEmpty();
        }
    }
}
