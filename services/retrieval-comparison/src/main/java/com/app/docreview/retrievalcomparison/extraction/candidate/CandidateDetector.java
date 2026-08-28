package com.app.docreview.retrievalcomparison.extraction.candidate;

import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.extraction.CandidateCategory;
import com.app.docreview.contracts.extraction.RequirementCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic detection of likely normative regions. Architecture §12.2.
 *
 * <p><strong>A pre-filter, not an extractor.</strong> Its only job is deciding which source regions
 * are worth an LLM call — recall matters far more than precision here, because a region never sent
 * is a requirement that can never be extracted, evaluated, or reported as a gap. A false positive
 * costs one model call and is discarded by validation; a false negative is invisible.
 *
 * <p>That asymmetry is why this errs toward including. It is also why the detector deliberately does
 * NOT try to identify the actor, action, or condition: those need semantics, and a regex that
 * appears to produce them yields confident nonsense — "The rollback plan must identify the steps"
 * gives {@code actor = "rollback plan"}, a grammatical subject that cannot bear an obligation.
 * Structure and lexical cues here; meaning from the model (§12.4).
 */
@Component
@Slf4j
public class CandidateDetector {

    /** Bump on any behavioural change: it is persisted with every requirement set (§12.9). */
    public static final String DETECTOR_VERSION = "CANDIDATE-V1";

    /** Obligation cues. Ordered longest-first so "must not" is tested before "must". */
    private static final List<Pattern> OBLIGATION = List.of(
            Pattern.compile("\\bis\\s+required\\s+to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bare\\s+required\\s+to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bis\\s+responsible\\s+for\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bare\\s+responsible\\s+for\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bis\\s+required\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmust\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshall\\b", Pattern.CASE_INSENSITIVE));

    /**
     * Prohibition cues.
     *
     * <p>Checked BEFORE obligations because "must not" contains "must". Classifying a prohibition as
     * an obligation inverts its meaning, and a comparison then reports a compliant process as
     * violating and vice versa — the single most damaging misclassification available here.
     */
    private static final List<Pattern> PROHIBITION = List.of(
            Pattern.compile("\\bmust\\s+not\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshall\\s+not\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmay\\s+not\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bis\\s+prohibited\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bare\\s+prohibited\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bno\\s+person\\s+may\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmay\\s+only\\b", Pattern.CASE_INSENSITIVE));

    /** Conditional scope cues (§12.2). */
    private static final List<Pattern> CONDITIONAL = List.of(
            Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bunless\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexcept\\s+(?:where|when|if)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bprovided\\s+that\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bif\\b", Pattern.CASE_INSENSITIVE));

    /** Advisory cues — recorded but not evaluated by default (§12.2). */
    private static final List<Pattern> ADVISORY = List.of(
            Pattern.compile("\\bshould\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bis\\s+encouraged\\s+to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmay\\b", Pattern.CASE_INSENSITIVE));

    /**
     * Timing and threshold cues (§12.2).
     *
     * <p>These promote a region even without a modal verb: "Approval evidence is retained for seven
     * years" is a hard requirement with no "must" in it. A modal-only detector misses the entire
     * class of retention and timing obligations, which are among the most commonly audited.
     */
    private static final List<Pattern> TIMING = List.of(
            Pattern.compile("\\bbefore\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bafter\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwithin\\s+\\w+\\s+(?:business\\s+)?(?:day|days|hour|hours|week|weeks|month|months)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bat\\s+least\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bno\\s+later\\s+than\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bretained\\s+for\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bannually\\b|\\bquarterly\\b|\\bmonthly\\b", Pattern.CASE_INSENSITIVE));

    /**
     * Section titles whose content is structurally non-normative.
     *
     * <p>A definitions section says "a production system MEANS any system serving external
     * customers" — lexically similar to a requirement and not one. §12.8 rule 9 makes promoting
     * these to mandatory controls a validation failure, so recognizing them here avoids wasted model
     * calls and a class of false requirement.
     */
    private static final Pattern NON_NORMATIVE_SECTION = Pattern.compile(
            "^\\s*(?:\\d+(?:\\.\\d+)*\\.?\\s+)?"
            + "(?:definitions?|glossary|terms(?:\\s+and\\s+definitions)?|scope|purpose"
            + "|introduction|preamble|背景|background|references?|revision\\s+history"
            + "|document\\s+control|examples?|rationale|appendix)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Section titles that strongly indicate normative content (§12.2). */
    private static final Pattern NORMATIVE_SECTION = Pattern.compile(
            "\\b(?:requirements?|controls?|obligations?|mandatory|responsibilit(?:y|ies)"
            + "|standards?|policy|procedures?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Groups spans into candidates and classifies each group.
     *
     * <p>Grouping is by SECTION (§12.3): a heading plus the paragraphs beneath it form one obligation
     * more often than not, and splitting them discards the qualification that makes the requirement
     * precise.
     */
    public List<RequirementCandidate> detect(List<EvidenceSpan> spans,
                                             java.util.function.Function<String, NodeType> nodeTypeOf) {
        List<RequirementCandidate> candidates = new ArrayList<>();
        List<EvidenceSpan> group = new ArrayList<>();
        List<String> groupSection = null;
        int index = 0;

        for (EvidenceSpan span : spans) {
            boolean sectionChanged = groupSection != null && !groupSection.equals(span.sectionPath());
            if (sectionChanged && !group.isEmpty()) {
                var candidate = classifyGroup(group, groupSection, index);
                if (candidate != null) {
                    candidates.add(candidate);
                    index++;
                }
                group = new ArrayList<>();
            }
            group.add(span);
            groupSection = span.sectionPath();
        }
        if (!group.isEmpty()) {
            var candidate = classifyGroup(group, groupSection, index);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        log.info("Detected {} requirement candidates from {} spans ({})",
                candidates.size(), spans.size(), DETECTOR_VERSION);
        return candidates;
    }

    /** Returns null when a group carries no normative signal at all. */
    private RequirementCandidate classifyGroup(List<EvidenceSpan> group, List<String> sectionPath,
                                               int index) {
        String combined = String.join(" ", group.stream().map(EvidenceSpan::exactText).toList());
        Set<String> signals = new LinkedHashSet<>();

        boolean nonNormativeSection = sectionPath != null && !sectionPath.isEmpty()
                && NON_NORMATIVE_SECTION.matcher(sectionPath.get(sectionPath.size() - 1)).find();

        boolean prohibition = matchesAny(PROHIBITION, combined, signals, "prohibition");
        boolean obligation = matchesAny(OBLIGATION, combined, signals, "obligation");
        boolean conditional = matchesAny(CONDITIONAL, combined, signals, "conditional");
        boolean advisory = matchesAny(ADVISORY, combined, signals, "advisory");
        boolean timing = matchesAny(TIMING, combined, signals, "timing");

        if (sectionPath != null && sectionPath.stream().anyMatch(h -> NORMATIVE_SECTION.matcher(h).find())) {
            signals.add("normative-section");
        }
        if (nonNormativeSection) {
            signals.add("non-normative-section");
        }

        CandidateCategory category;
        if (nonNormativeSection && !prohibition && !obligation) {
            // A definitions or scope section with no modal verb: recorded as a DEFINITION so it is
            // visibly excluded rather than silently dropped.
            category = CandidateCategory.DEFINITION;
        } else if (prohibition) {
            // Prohibition wins over obligation: "must not" contains "must".
            category = CandidateCategory.PROHIBITION;
        } else if (obligation && conditional) {
            category = CandidateCategory.CONDITIONAL_REQUIREMENT;
        } else if (obligation || timing) {
            // Timing alone qualifies: "retained for seven years" has no modal verb.
            category = CandidateCategory.MANDATORY_REQUIREMENT;
        } else if (advisory) {
            category = CandidateCategory.ADVISORY_GUIDANCE;
        } else {
            return null;   // no normative signal; not worth a model call
        }

        return new RequirementCandidate(
                "CAND-" + index,
                category,
                group.stream().map(EvidenceSpan::spanId).toList(),
                sectionPath == null ? List.of() : sectionPath,
                combined,
                List.copyOf(signals),
                extractControlId(sectionPath));
    }

    private boolean matchesAny(List<Pattern> patterns, String text, Set<String> signals, String label) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                signals.add(label + ":" + p.pattern().replaceAll("\\\\b|\\\\s\\+", " ").trim());
                return true;
            }
        }
        return false;
    }

    /**
     * Preserves the standard's own control number when present (§12.7).
     *
     * <p>A reviewer who knows the standard navigates by {@code 4.2}, not by a generated ID. Replacing
     * their numbering makes findings harder to trust, not easier.
     */
    private String extractControlId(List<String> sectionPath) {
        if (sectionPath == null || sectionPath.isEmpty()) {
            return null;
        }
        var matcher = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)").matcher(sectionPath.get(sectionPath.size() - 1));
        return matcher.find() ? matcher.group(1) : null;
    }
}
