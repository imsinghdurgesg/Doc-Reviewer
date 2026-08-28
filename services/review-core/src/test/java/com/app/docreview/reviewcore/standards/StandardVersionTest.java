package com.app.docreview.reviewcore.standards;

import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparability rule. Architecture §8.1.
 *
 * <p>This is the check that prevents the system's most dangerous failure: an active standard with no
 * extracted requirements yields a comparison with no findings, which reads as full compliance.
 */
class StandardVersionTest {

    private StandardVersion version() {
        return new StandardVersion("std-1", "2.4", "sha", "key", "s.pdf", "application/pdf", 10, "u");
    }

    @Nested
    @DisplayName("a version is comparable only when active AND indexed")
    class Comparability {

        @Test
        void newVersionIsNotComparable() {
            // Fresh upload: DRAFT + UPLOADED.
            assertThat(version().comparable()).isFalse();
        }

        @Test
        void activeButNotIndexedIsNotComparable() {
            var v = version();
            v.setLifecycle(StandardLifecycle.ACTIVE);
            assertThat(v.comparable())
                    .as("active but unindexed has no requirements; comparisons would look compliant")
                    .isFalse();
        }

        @Test
        void indexedButNotActiveIsNotComparable() {
            var v = version();
            v.setIndexingState(StandardIndexingState.INDEXED);
            assertThat(v.comparable())
                    .as("a draft must not be silently used for comparisons")
                    .isFalse();
        }

        @Test
        void activeAndIndexedIsComparable() {
            var v = version();
            v.setLifecycle(StandardLifecycle.ACTIVE);
            v.setIndexingState(StandardIndexingState.INDEXED);
            assertThat(v.comparable()).isTrue();
        }

        @Test
        void deprecatedIsNotComparableEvenWhenIndexed() {
            var v = version();
            v.setLifecycle(StandardLifecycle.DEPRECATED);
            v.setIndexingState(StandardIndexingState.INDEXED);
            assertThat(v.comparable())
                    .as("deprecated versions stay resolvable for history but are not offered anew")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("content identity fields have no setters")
    void contentFieldsAreImmutable() throws Exception {
        // Immutability is the guarantee historical reproducibility rests on (§19.2), so assert it
        // structurally rather than trusting review to catch a future setter.
        for (String field : new String[]{"version", "contentSha256", "sourceObjectKey", "createdAt"}) {
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            assertThat(StandardVersion.class.getMethods())
                    .as("%s must not be mutable", field)
                    .noneMatch(m -> m.getName().equals(setter));
        }
    }
}
