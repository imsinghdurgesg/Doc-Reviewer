package com.app.docreview.retrievalcomparison.retrieval;

import com.app.docreview.retrievalcomparison.retrieval.document.AssessmentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metadata reorders candidates; it must never decide them. Architecture §15.1.
 *
 * <p>The department, family, and document type of an uploaded document are INFERRED, typically at
 * around 0.9 confidence each. If they could dominate relevance, a single wrong inference would select
 * the wrong standard and the assessment would measure a document against a policy that does not govern
 * it — reporting every requirement as a gap.
 *
 * <p>So the property under test is not "matching helps" but "matching cannot help enough to overturn
 * relevance". That bound is the whole safety argument for using soft ranking instead of hard filters.
 */
class AssessmentContextTest {

    private static final String DEPT = "PROCUREMENT";
    private static final String FAMILY = "THIRD_PARTY_RISK";
    private static final String TYPE = "PROCUREMENT_PROCEDURE";

    @Test
    @DisplayName("no context leaves every score untouched")
    void emptyContextIsNeutral() {
        // The automatic path passes no context, and must behave exactly as before.
        var none = AssessmentContext.none();

        assertThat(none.weight(DEPT, FAMILY, TYPE)).isEqualTo(1.0);
        assertThat(none.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a full match promotes, a full mismatch demotes")
    void matchesAndMismatches() {
        var context = new AssessmentContext(DEPT, FAMILY, TYPE, null);

        assertThat(context.weight(DEPT, FAMILY, TYPE)).isGreaterThan(1.0);
        assertThat(context.weight("FINANCE", "PRIVACY", "INCIDENT_RUNBOOK")).isLessThan(1.0);
    }

    @Test
    @DisplayName("a missing value on either side is neutral, not a mismatch")
    void absentMetadataIsNotPenalised() {
        // Most standards have incomplete metadata. Treating absence as a mismatch would push a
        // correctly-relevant standard below one that merely happens to carry the right label.
        var context = new AssessmentContext(DEPT, FAMILY, TYPE, null);

        assertThat(context.weight(null, null, null)).isEqualTo(1.0);
        assertThat(context.weight("", "  ", null)).isEqualTo(1.0);

        var noContext = new AssessmentContext(null, null, null, "focus on approvals");
        assertThat(noContext.weight(DEPT, FAMILY, TYPE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("matching cannot lift a clearly less relevant standard above a more relevant one")
    void weightCannotOverturnRelevance() {
        // THE safety property. A standard at 0.50 raw relevance with every label matching must stay
        // below one at 0.70 with every label wrong — otherwise one bad inference decides the
        // assessment.
        var context = new AssessmentContext(DEPT, FAMILY, TYPE, null);

        double flatteredWeak = 0.50 * context.weight(DEPT, FAMILY, TYPE);
        double penalisedStrong = 0.70 * context.weight("FINANCE", "PRIVACY", "INCIDENT_RUNBOOK");

        assertThat(flatteredWeak).isLessThan(penalisedStrong);
    }

    @Test
    @DisplayName("the weight stays within a bounded range")
    void weightIsBounded() {
        var context = new AssessmentContext(DEPT, FAMILY, TYPE, null);

        double best = context.weight(DEPT, FAMILY, TYPE);
        double worst = context.weight("X", "Y", "Z");

        // Stated as explicit bounds so widening them is a deliberate edit with a failing test, not a
        // quiet tuning change that erodes the property above.
        // Tight bounds, pinned deliberately. The first attempt multiplied three per-dimension factors
        // that each looked modest and compounded to a 2.02 best/worst ratio — enough for metadata to
        // outvote a 0.20 gap in relevance. Widening these must be a deliberate edit with a failing
        // test, not a quiet tuning change.
        assertThat(best).isLessThanOrEqualTo(1.20);
        assertThat(worst).isGreaterThanOrEqualTo(0.85);
        assertThat(best / worst).isLessThan(1.40);
    }

    @Test
    @DisplayName("comparison ignores case and surrounding whitespace")
    void comparisonIsLenient() {
        // Values arrive from a form, a folder name, and a model. Requiring exact bytes would make the
        // signal fail for cosmetic reasons.
        var context = new AssessmentContext("procurement", FAMILY, TYPE, null);

        assertThat(context.weight("  PROCUREMENT ", FAMILY, TYPE))
                .isEqualTo(context.weight("PROCUREMENT", FAMILY, TYPE));
    }

    @Test
    @DisplayName("guidance alone is a context, even with no metadata")
    void guidanceCountsAsContext() {
        // Reviewer guidance must switch selection into review-list behaviour — it means the reviewer is
        // choosing, which is when a lower-ranked candidate becomes worth showing.
        var guidanceOnly = new AssessmentContext(null, null, null, "emergency changes only");

        assertThat(guidanceOnly.isEmpty()).isFalse();
    }
}
