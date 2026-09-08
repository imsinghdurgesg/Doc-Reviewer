package com.app.docreview.reviewcore.comparison;

import com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped defaults and bounds for Admin-configurable review limits. Architecture §10.5, §15.5.
 *
 * <p><strong>Why the defaults are asserted at all.</strong> Both values became configurable, and a
 * setting with a permissive default is worse than a constant: a deployment that never opens the Admin
 * screen inherits whatever the field initialiser happens to say, and nobody notices until a bill or a
 * duplicated findings list. These pin the conservative starting point.
 */
class ReviewLimitsDefaultsTest {

    @Test
    @DisplayName("one recommended standard by default")
    void oneRecommendedStandard() {
        // The measurement behind it: three standards derived from a common framework produced 118
        // findings where one produced 65 — the same control three times, with nothing to tell a
        // reviewer whether that is three problems or one.
        assertThat(new ReviewLimitsConfig().getRecommendedStandards()).isEqualTo(1);
    }

    @Test
    @DisplayName("the requirement cap defaults to 500, never zero or unlimited")
    void requirementCapDefault() {
        // Zero is the dangerous value here, not a large one: it would reject every comparison, and the
        // reviewer would see "0 of 0 requirements" with nothing naming the setting that caused it.
        assertThat(new ReviewLimitsConfig().getMaxRequirementsPerComparison()).isEqualTo(500);
    }

    @Test
    @DisplayName("both settings are bounded, so configurable does not mean unlimited")
    void boundsExist() {
        // Each extra standard multiplies model calls and duplicates findings; each extra requirement
        // is another model call. An administrator must not be able to remove the ceiling entirely.
        // No constant ceiling on recommendations any more: any number here was arbitrary, and the
        // meaningful bound is how many standards exist to recommend. Asserted by absence, so
        // reintroducing a constant is a deliberate act rather than a quiet one.
        assertThat(java.util.Arrays.stream(ReviewLimitsConfig.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("a fixed recommendation ceiling would forbid pre-selecting standards the "
                        + "selection screen already ranks")
                .doesNotContain("MAX_ALLOWED_RECOMMENDED");
        assertThat(ReviewLimitsConfig.MAX_ALLOWED_REQUIREMENTS).isBetween(500, 5000);
    }

    @Test
    @DisplayName("the id is the singleton constant, so a save REPLACES rather than inserts")
    void idIsTheSingletonConstant() {
        // THE defect this pins. The class declared SINGLETON_ID but had no @Id field, so Mongo
        // generated a fresh id on every save: each update inserted a NEW row, findById(SINGLETON_ID)
        // then matched nothing, and a value saved as 8 read back as 1 immediately.
        //
        // The symptom was baffling from the outside — "another call happens and sets it to 1" — while
        // the stored data showed 8 four times over, one row per save.
        assertThat(new ReviewLimitsConfig().getId())
                .as("an uninitialised id makes every save an insert, so nothing ever reads back")
                .isEqualTo(ReviewLimitsConfig.SINGLETON_ID);
    }
}
