package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.NormativeStrength;
import com.app.docreview.contracts.enums.Severity;
import com.app.docreview.contracts.requirement.AtomicClause;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic severity. Architecture §17.7.
 *
 * <p>Severity must be defensible without reference to a model response, because a reviewer will be
 * asked why a finding was ranked HIGH.
 */
class SeverityResolverTest {

    private final SeverityResolver resolver = new SeverityResolver();

    private ExtractedRequirement requirement(String title, String text, NormativeStrength strength) {
        return new ExtractedRequirement("R1", null, null, title, List.of("SPAN-1"),
                strength, text,
                List.of(new AtomicClause("R1-A", "x", "do", "y", null, null, false)),
                null, List.of(), 0.9);
    }

    @Test
    @DisplayName("standard metadata wins outright")
    void metadataTakesPrecedence() {
        // §17.7 step 1. If the standard publishes a severity, nothing should second-guess it.
        var r = requirement("Rehearse rollback", "Teams should rehearse.", NormativeStrength.ADVISORY);
        assertThat(resolver.resolve(r, Severity.HIGH)).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("segregation of duties is HIGH by rule")
    void segregationOfDutiesIsHigh() {
        var r = requirement("Segregation of duties",
                "No person may both approve and implement the same production change.",
                NormativeStrength.PROHIBITION);
        assertThat(resolver.resolve(r, null)).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("retention obligations are MEDIUM by rule")
    void retentionIsMedium() {
        var r = requirement("Approval retention",
                "Approval evidence is retained for seven years.", NormativeStrength.MANDATORY);
        assertThat(resolver.resolve(r, null)).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("a prohibition defaults higher than an obligation")
    void prohibitionFallbackIsHigh() {
        // Doing something forbidden is a worse position than omitting a step, so the fallback
        // reflects that rather than treating all requirements alike.
        var r = requirement("Unnamed control", "The team must not skip the gate.",
                NormativeStrength.PROHIBITION);
        assertThat(resolver.resolve(r, null)).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("advisory requirements are INFORMATIONAL, never a gap to chase")
    void advisoryIsInformational() {
        var r = requirement("Nice to have", "Consider periodic reviews.", NormativeStrength.ADVISORY);
        assertThat(resolver.resolve(r, null)).isEqualTo(Severity.INFORMATIONAL);
    }

    @Test
    @DisplayName("an unremarkable obligation is MEDIUM, not HIGH")
    void obligationFallbackIsMedium() {
        // The failure mode this guards against: everything ranked HIGH, which makes the ranking
        // useless. Models drift that way; a deterministic default does not.
        var r = requirement("Record the change",
                "Every change must be recorded in the register.", NormativeStrength.MANDATORY);
        assertThat(resolver.resolve(r, null)).isEqualTo(Severity.MEDIUM);
    }
}
