package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.enums.Severity;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Determines severity deterministically. Architecture §13.1, §17.7.
 *
 * <p><strong>Never from the model.</strong> §17.7 is explicit: the LLM may explain impact but must
 * not set authoritative severity. An LLM-assigned severity is unauditable — it cannot be justified,
 * reproduced, or appealed — and in practice models drift toward marking everything HIGH, which
 * destroys the ranking that makes a findings list usable.
 *
 * <p>Resolution order follows §17.7: explicit standard metadata, then administrator rules, then a
 * documented fallback. Only the last two are implemented here; metadata arrives with Phase 2's
 * standard model and is passed in when present.
 */
@Component
public class SeverityResolver {

    /**
     * Administrator-configurable keyword rules (§17.7 step 2).
     *
     * <p>These encode control families whose failure is materially worse: segregation of duties and
     * unauthorized production access are the classic audit findings. Configurable rather than
     * hardcoded is the intent; this map is the documented default.
     */
    private static final Map<Pattern, Severity> RULES = Map.of(
            Pattern.compile("segregation\\s+of\\s+duties|both\\s+approve\\s+and\\s+implement",
                    Pattern.CASE_INSENSITIVE), Severity.HIGH,
            Pattern.compile("unauthori[sz]ed|privileged\\s+access|production\\s+access",
                    Pattern.CASE_INSENSITIVE), Severity.HIGH,
            Pattern.compile("emergency|incident|bypass", Pattern.CASE_INSENSITIVE), Severity.HIGH,
            Pattern.compile("retention|retained\\s+for|audit\\s+trail",
                    Pattern.CASE_INSENSITIVE), Severity.MEDIUM,
            Pattern.compile("rehears|train|awareness", Pattern.CASE_INSENSITIVE), Severity.LOW);

    /**
     * @param metadataSeverity from the standard itself when it publishes one; wins outright (§17.7)
     */
    public Severity resolve(ExtractedRequirement requirement, Severity metadataSeverity) {
        if (metadataSeverity != null) {
            return metadataSeverity;
        }

        String haystack = String.join(" ",
                requirement.title() == null ? "" : requirement.title(),
                requirement.normalizedRequirement() == null ? "" : requirement.normalizedRequirement());

        for (var entry : RULES.entrySet()) {
            if (entry.getKey().matcher(haystack).find()) {
                return entry.getValue();
            }
        }

        // Documented fallback (§17.7 step 3). A prohibition defaults higher than an obligation: a
        // process that does something forbidden is a worse position than one that omits a step.
        if (requirement.normativeStrength() == NormativeStrength.PROHIBITION) {
            return Severity.HIGH;
        }
        if (requirement.normativeStrength() == NormativeStrength.ADVISORY) {
            return Severity.INFORMATIONAL;
        }
        return Severity.MEDIUM;
    }
}
