package com.app.docreview.reviewcore.shared;

import com.app.docreview.contracts.job.ComparisonState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One unreadable comparison must not break the list. Architecture §19.2, §21.
 *
 * <p><strong>The incident.</strong> A comparison was written with state {@code COMPARING_REQUIREMENTS},
 * which is not a {@link ComparisonState}. Deserialisation threw inside the query, so it did not degrade
 * that one row — it aborted the whole result set, and History, recent-comparisons and used-standards
 * returned 500 for every user despite 47 of the 48 records being valid.
 *
 * <p>A single bad row taking down a shared list is the property being fixed here. The bad write was also
 * corrected, but the next one should cost one row, not the page.
 */
class ComparisonStateReadConverterTest {

    private final MongoEnumResilienceConfig.ComparisonStateReadConverter converter =
            new MongoEnumResilienceConfig.ComparisonStateReadConverter();

    @Test
    @DisplayName("every real enum value round-trips")
    void knownValuesConvert() {
        for (var state : ComparisonState.values()) {
            assertThat(converter.convert(state.name()))
                    .as("%s must map to itself", state)
                    .isEqualTo(state);
        }
    }

    @Test
    @DisplayName("the exact value that broke History no longer throws")
    void theOffendingValueIsTolerated() {
        assertThatCode(() -> converter.convert("COMPARING_REQUIREMENTS")).doesNotThrowAnyException();
        assertThat(converter.convert("COMPARING_REQUIREMENTS")).isEqualTo(ComparisonState.FAILED);
    }

    @Test
    @DisplayName("an unknown state reads as FAILED, never as in-progress")
    void unknownReadsAsTerminal() {
        // FAILED rather than null or an in-flight value, deliberately. A non-terminal reading would
        // flow into Comparison.terminal() as "still running", and the recovery sweep would
        // re-dispatch an unreadable comparison forever.
        for (var bad : new String[]{"COMPARING_REQUIREMENTS", "WHATEVER", "completed_maybe", "123"}) {
            assertThat(converter.convert(bad))
                    .as("%s is uninterpretable and must read as terminal", bad)
                    .isEqualTo(ComparisonState.FAILED);
        }
    }

    @Test
    @DisplayName("null and blank read as FAILED rather than throwing")
    void missingStateIsTolerated() {
        // A missing state is as uninterpretable as an unknown one; the same reasoning applies.
        assertThat(converter.convert(null)).isEqualTo(ComparisonState.FAILED);
        assertThat(converter.convert("")).isEqualTo(ComparisonState.FAILED);
        assertThat(converter.convert("   ")).isEqualTo(ComparisonState.FAILED);
    }

    @Test
    @DisplayName("case and surrounding whitespace are tolerated on read")
    void normalisesBeforeLookup() {
        // Not laxness for its own sake: a hand-written repair or a migration can easily produce
        // "completed", and reading that as FAILED would misreport a finished review.
        assertThat(converter.convert("completed")).isEqualTo(ComparisonState.COMPLETED);
        assertThat(converter.convert("  COMPLETED  ")).isEqualTo(ComparisonState.COMPLETED);
    }
}
