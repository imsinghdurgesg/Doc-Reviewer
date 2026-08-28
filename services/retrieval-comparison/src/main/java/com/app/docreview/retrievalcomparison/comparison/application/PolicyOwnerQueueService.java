package com.app.docreview.retrievalcomparison.comparison.application;

import com.app.docreview.contracts.enums.ConditionReviewStatus;
import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverride;
import com.app.docreview.retrievalcomparison.comparison.domain.RequirementConditionOverrideRepository;
import com.app.docreview.retrievalcomparison.extraction.domain.StandardRequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The open applicability questions awaiting a policy owner. Architecture §12.5, §17.6, §19.2.
 *
 * <p><strong>Why a queue rather than a notification.</strong> An unresolved condition is not an incident
 * to be alerted on; it is a standing decision nobody has made. It stays open until someone with the
 * authority to interpret the policy rules on it, and it must be visible for as long as that takes —
 * an email is read once and then gone, while the requirement keeps being compared under uncertainty.
 *
 * <p><strong>Grouped by condition text, not by row.</strong> The same phrase recurs across versions of a
 * standard, so a per-row queue would present one decision as several unrelated small ones. Grouping also
 * exposes the more useful signal: where the SAME phrase was classified differently in different
 * versions, the extractor is demonstrably unreliable on it, and that is worth a policy owner knowing
 * before they rule.
 *
 * <p><strong>Nothing here blocks a comparison.</strong> Every queued condition resolves to
 * {@link ConditionRole#UNKNOWN} and cannot exclude, so the requirement is compared and flagged. The
 * queue exists so uncertainty is actionable, not so it becomes a gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PolicyOwnerQueueService {

    private final RequirementConditionOverrideRepository overrides;
    private final StandardRequirementRepository requirements;

    /**
     * One open question, with everything needed to answer it.
     *
     * @param conditionText      the phrase as extracted, verbatim
     * @param occurrences        how many places it appears. A phrase appearing many times is worth
     *                           ruling on before one appearing once.
     * @param requirementTexts   the clauses it was extracted from. Load-bearing: the extractor
     *                           truncated "When the change is a material security or privacy change"
     *                           to "When it is", which is unjudgeable alone and obvious in context.
     * @param extractedRoles     what the model said, across occurrences. More than one value means the
     *                           extractor contradicted itself and its opinion carries little weight.
     * @param truncated          the phrase looks like a fragment rather than a whole condition, so the
     *                           right answer may be to fix extraction rather than to rule on it.
     */
    public record OpenQuestion(
            String conditionText,
            int occurrences,
            List<String> standardVersionIds,
            List<String> requirementIds,
            List<String> requirementTexts,
            List<String> extractedRoles,
            String reviewerNotes,
            String reviewBatchId,
            boolean inconsistentlyExtracted,
            boolean truncated) {
    }

    /**
     * A phrase shorter than this is treated as a probable truncation.
     *
     * <p>A deliberately crude signal, and it does not decide anything — it only annotates a row so a
     * reader looks at the clause before ruling. Real conditions this short exist ("before signing"), so
     * this must never gate or auto-resolve anything.
     */
    private static final int TRUNCATION_LENGTH = 18;

    /** Open questions, most-recurring first. */
    public List<OpenQuestion> openQuestions() {
        List<RequirementConditionOverride> unresolved;
        try {
            unresolved = overrides.findByReviewStatus(ConditionReviewStatus.NEEDS_POLICY_OWNER);
        } catch (Exception e) {
            // Class name only (§35). An empty queue is the safe degradation: nothing is blocked by it,
            // so a store failure must not fail the page that shows everything else.
            log.warn("Could not read the policy-owner queue: {}", e.getClass().getSimpleName());
            return List.of();
        }

        // LinkedHashMap: grouping order is stable, so the same data renders the same way twice.
        Map<String, List<RequirementConditionOverride>> byText = new LinkedHashMap<>();
        for (var row : unresolved) {
            var key = row.getConditionText() == null
                    ? "" : row.getConditionText().trim().toLowerCase(Locale.ROOT);
            byText.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        var questions = new ArrayList<OpenQuestion>(byText.size());
        byText.forEach((key, rows) -> questions.add(toQuestion(rows)));

        // Most occurrences first: a phrase in twelve places is a better use of a policy owner's
        // attention than one in a single place, and the queue should say so by ordering.
        questions.sort((a, b) -> Integer.compare(b.occurrences(), a.occurrences()));
        return List.copyOf(questions);
    }

    private OpenQuestion toQuestion(List<RequirementConditionOverride> rows) {
        var first = rows.getFirst();

        // TreeSet: de-duplicated and stably ordered. The same requirement id recurs across versions,
        // and a list repeating it tells a reader nothing.
        var versionIds = new TreeSet<String>();
        var requirementIds = new TreeSet<String>();
        var extractedRoles = new TreeSet<String>();
        var texts = new TreeSet<String>();

        for (var row : rows) {
            versionIds.add(row.getStandardVersionId());
            requirementIds.add(row.getRequirementId());
            if (row.getExtractedRole() != null) {
                extractedRoles.add(row.getExtractedRole().name());
            }
            requirementText(row).ifPresent(texts::add);
        }

        var text = first.getConditionText() == null ? "" : first.getConditionText().trim();
        return new OpenQuestion(
                text,
                rows.size(),
                List.copyOf(versionIds),
                List.copyOf(requirementIds),
                List.copyOf(texts),
                List.copyOf(extractedRoles),
                first.getReviewerNotes(),
                first.getReviewBatchId(),
                // Reported rather than resolved: a contradiction is evidence about the EXTRACTOR, and
                // silently picking the majority answer would hide exactly that.
                extractedRoles.size() > 1,
                !text.isEmpty() && text.length() < TRUNCATION_LENGTH);
    }

    /** The clause a condition came from, when it can still be found. */
    private java.util.Optional<String> requirementText(RequirementConditionOverride row) {
        try {
            return requirements
                    .findByStandardVersionIdAndRequirementSetVersion(
                            row.getStandardVersionId(), row.getRequirementSetVersion())
                    .stream()
                    .filter(r -> row.getRequirementId().equals(r.getRequirementId()))
                    .findFirst()
                    .map(r -> r.getNormalizedRequirement())
                    .filter(t -> t != null && !t.isBlank());
        } catch (Exception e) {
            // A missing clause is not fatal to the queue — the question is still open, and saying so
            // without its evidence beats not showing it at all.
            log.debug("No requirement text for a queued condition: {}", e.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
    }
}
