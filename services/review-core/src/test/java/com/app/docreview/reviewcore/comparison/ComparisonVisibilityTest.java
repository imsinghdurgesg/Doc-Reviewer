package com.app.docreview.reviewcore.comparison;

import com.app.docreview.reviewcore.comparison.application.ComparisonService;
import com.app.docreview.reviewcore.comparison.domain.Comparison;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A reviewer sees only their own comparisons. Architecture §33.
 *
 * <p>Every authenticated Stanford account holds REVIEWER, and history was unscoped — so anyone who
 * logged in could read every review in the organization, including the findings on documents someone
 * else uploaded. Only ADMIN sees everything now.
 *
 * <p>What these cases pin is the SHAPE of the boundary rather than the happy path:
 *
 * <ul>
 *   <li>a non-owner gets NOT FOUND, never FORBIDDEN — a 403 confirms the comparison exists, which
 *       leaks that a particular document was reviewed;</li>
 *   <li>an unattributed comparison is admin-only, so legacy rows are not a hole;</li>
 *   <li>ownership is exact equality, so no prefix or case trick reaches another account.</li>
 * </ul>
 */
class ComparisonVisibilityTest {

    private ComparisonRepository comparisons;
    private ComparisonService service;

    private static final String OWNER = "owner@stanford.edu";
    private static final String OTHER = "other@stanford.edu";

    @BeforeEach
    void setUp() {
        comparisons = Mockito.mock(ComparisonRepository.class);
        service = new ComparisonService(
                comparisons,
                defaultLimits(),
                Mockito.mock(UploadedDocumentRepository.class),
                Mockito.mock(StandardVersionRepository.class),
                Mockito.mock(JobDispatcher.class),
                Mockito.mock(com.app.docreview.reviewcore.workflow.ComparisonFindingReader.class));
    }

    private void stored(String publicId, String createdBy) {
        var comparison = new Comparison(publicId, "PROC-1", List.of("STD-V1"), createdBy);
        Mockito.when(comparisons.findByPublicId(publicId)).thenReturn(Optional.of(comparison));
    }

    @Nested
    @DisplayName("reading one comparison")
    class ReadOne {

        @Test
        @DisplayName("the owner can read their own")
        void ownerCanRead() {
            stored("CMP-1", OWNER);

            assertThat(service.getVisibleTo("CMP-1", OWNER).getPublicId()).isEqualTo("CMP-1");
        }

        @Test
        @DisplayName("a non-owner gets NOT FOUND, not FORBIDDEN")
        void nonOwnerGetsNotFound() {
            // The distinction matters: a 403 would confirm the comparison exists, and a public id is
            // guessable enough to probe on a shared deployment.
            stored("CMP-1", OWNER);

            assertThatThrownBy(() -> service.getVisibleTo("CMP-1", OTHER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Comparison");
        }

        @Test
        @DisplayName("an administrator reads any comparison")
        void adminBypassesTheCheck() {
            // null is the unrestricted scope the controller passes for ADMIN.
            stored("CMP-1", OWNER);

            assertThat(service.getVisibleTo("CMP-1", null).getPublicId()).isEqualTo("CMP-1");
        }

        @Test
        @DisplayName("an unattributed comparison is admin-only")
        void legacyRowsAreAdminOnly() {
            // Stored before attribution was recorded. Treating a missing owner as readable-by-anyone
            // would make legacy rows the one gap in this boundary.
            stored("CMP-OLD", null);

            assertThat(service.getVisibleTo("CMP-OLD", null).getPublicId()).isEqualTo("CMP-OLD");
            assertThatThrownBy(() -> service.getVisibleTo("CMP-OLD", OWNER))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("ownership is exact, so no near-match reaches another account")
        void ownershipIsExact() {
            stored("CMP-1", OWNER);

            // A prefix, a suffix, and a case variant must all fail. If this check were a regex or a
            // startsWith, one of these would pass and read as a bug about email formatting rather
            // than an authorization failure.
            assertThatThrownBy(() -> service.getVisibleTo("CMP-1", "owner@stanford.ed"))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.getVisibleTo("CMP-1", "xowner@stanford.edu"))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.getVisibleTo("CMP-1", "OWNER@stanford.edu"))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("recent list")
    class Recent {

        @Test
        @DisplayName("a reviewer's list is queried by owner")
        void scopedByOwner() {
            Mockito.when(comparisons.findTop50ByCreatedByOrderByCreatedAtDesc(OWNER))
                    .thenReturn(List.of());

            service.recent(OWNER);

            // The SCOPED query, and never the unscoped one — calling the wrong repository method is
            // exactly how this leak would come back.
            Mockito.verify(comparisons).findTop50ByCreatedByOrderByCreatedAtDesc(OWNER);
            Mockito.verify(comparisons, Mockito.never()).findTop50ByOrderByCreatedAtDesc();
        }

        @Test
        @DisplayName("an administrator's list is unscoped")
        void adminSeesAll() {
            Mockito.when(comparisons.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());

            service.recent(null);

            Mockito.verify(comparisons).findTop50ByOrderByCreatedAtDesc();
            Mockito.verify(comparisons, Mockito.never())
                    .findTop50ByCreatedByOrderByCreatedAtDesc(Mockito.anyString());
        }
    }

    /**
     * A limits service returning the shipped defaults.
     *
     * <p>These tests are about visibility, not the cap, so the default is the honest stand-in: a mock
     * returning zero would reject every comparison and fail these assertions for an unrelated reason.
     */
    private static com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService defaultLimits() {
        var limits = Mockito.mock(
                com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService.class);
        Mockito.when(limits.current())
                .thenReturn(new com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfig());
        return limits;
    }
}
