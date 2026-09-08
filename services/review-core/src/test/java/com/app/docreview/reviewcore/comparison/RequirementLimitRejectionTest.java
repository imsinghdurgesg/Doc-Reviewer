package com.app.docreview.reviewcore.comparison;

import com.app.docreview.contracts.job.StandardIndexingState;
import com.app.docreview.reviewcore.comparison.application.ComparisonService;
import com.app.docreview.reviewcore.comparison.domain.ComparisonRepository;
import com.app.docreview.reviewcore.shared.error.ApiException;
import com.app.docreview.reviewcore.standards.domain.StandardLifecycle;
import com.app.docreview.reviewcore.standards.domain.StandardVersion;
import com.app.docreview.reviewcore.standards.repository.StandardVersionRepository;
import com.app.docreview.reviewcore.upload.domain.UploadedDocument;
import com.app.docreview.reviewcore.upload.domain.UploadedDocumentRepository;
import com.app.docreview.reviewcore.workflow.JobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A selection larger than one comparison can evaluate is refused, not truncated. §15.5, §17.6.
 *
 * <p><strong>The defect.</strong> The worker caps requirements per comparison. Exceeding it truncated
 * the list silently: a three-standard selection of 187 requirements evaluated 120, giving the first
 * standard all 84, the second 36 of 44, and the third NOTHING. The reviewer chose three standards, paid
 * for a full run, and got an empty tab for one of them — while the coverage panel reported 120/120
 * evaluated and the comparison reported COMPLETED.
 *
 * <p>Refusing at creation is the honest behaviour: the reviewer is told before spending anything, with
 * the numbers and a remedy. Truncation still exists as a backstop and now records dropped requirements
 * as untested coverage rows, but a reviewer should never reach it.
 */
class RequirementLimitRejectionTest {

    private ComparisonRepository comparisons;
    private UploadedDocumentRepository documents;
    private StandardVersionRepository standardVersions;
    private JobDispatcher jobDispatcher;
    private ComparisonService service;

    /** The limit under test. Set explicitly so the assertions do not depend on the shipped default. */
    private static final int LIMIT = 120;

    @BeforeEach
    void setUp() {
        comparisons = Mockito.mock(ComparisonRepository.class);
        documents = Mockito.mock(UploadedDocumentRepository.class);
        standardVersions = Mockito.mock(StandardVersionRepository.class);
        jobDispatcher = Mockito.mock(JobDispatcher.class);
        // The cap is now Admin-configured and read per request, so it is STUBBED rather than
        // reflected onto a field. That also makes the test exercise the real path: a value that
        // reaches the check the same way a saved setting does.
        var limits = Mockito.mock(
                com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfigService.class);
        var config = new com.app.docreview.reviewcore.aiconfig.ReviewLimitsConfig();
        config.setMaxRequirementsPerComparison(LIMIT);
        when(limits.current()).thenReturn(config);

        service = new ComparisonService(comparisons, limits, documents, standardVersions,
                jobDispatcher,
                Mockito.mock(com.app.docreview.reviewcore.workflow.ComparisonFindingReader.class));

        var document = Mockito.mock(UploadedDocument.class);
        when(document.readyForComparison()).thenReturn(true);
        when(documents.findByDocumentVersionId("PROC-1")).thenReturn(Optional.of(document));
    }

    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fixture cannot set " + field, e);
        }
    }

    /** A comparable version contributing {@code materialCount} requirements. */
    private void seedStandard(String id, int materialCount) {
        var version = new StandardVersion("std-" + id, "1.0", "sha", "key",
                "s.pdf", "application/pdf", 10, "u");
        version.setLifecycle(StandardLifecycle.ACTIVE);
        version.setIndexingState(StandardIndexingState.INDEXED);
        version.setMaterialRequirementCount(materialCount);
        when(standardVersions.findById(id)).thenReturn(Optional.of(version));
    }

    @Test
    @DisplayName("the exact selection that used to truncate is now refused")
    void refusesTheObservedSelection() {
        // 84 + 44 + 59 = 187 against a limit of 120. Previously this ran and dropped 67.
        seedStandard("sv-1", 84);
        seedStandard("sv-2", 44);
        seedStandard("sv-3", 59);

        assertThatThrownBy(() -> service.create("PROC-1", List.of("sv-1", "sv-2", "sv-3"), null, "u"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    // BOTH numbers, so the reviewer can decide what to deselect rather than guess.
                    assertThat(e.getMessage()).contains("187");
                    assertThat(e.getMessage()).contains(String.valueOf(LIMIT));
                    // And the remedy, not just the refusal.
                    assertThat(e.getMessage()).containsIgnoringCase("select fewer");
                });

        // NOTHING was spent. The comparison is not created and no job is dispatched, which is the whole
        // point of refusing at creation rather than mid-run.
        verify(comparisons, never()).save(any());
        verify(jobDispatcher, never()).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a selection exactly at the limit is allowed")
    void allowsSelectionAtTheLimit() {
        // Boundary: the check must be `>`, not `>=`. Rejecting an exact fit would make the advertised
        // limit a lie by one requirement.
        seedStandard("sv-1", 60);
        seedStandard("sv-2", 60);
        when(comparisons.save(any())).thenAnswer(i -> i.getArgument(0));

        service.create("PROC-1", List.of("sv-1", "sv-2"), null, "u");

        verify(jobDispatcher).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a single oversized standard is refused too")
    void refusesOneOversizedStandard() {
        // Not only a multi-standard problem. One standard larger than the limit would be truncated
        // just as silently, and the reviewer selected only it.
        seedStandard("sv-big", LIMIT + 1);

        assertThatThrownBy(() -> service.create("PROC-1", List.of("sv-big"), null, "u"))
                .isInstanceOf(ApiException.class);
        verify(jobDispatcher, never()).dispatchRetrievalComparison(any(), any(), any(), any());
    }

    @Test
    @DisplayName("automatic selection is not blocked by the limit")
    void automaticSelectionIsUnaffected() {
        // An empty selection asks the platform to choose (§15.1), and it selects ONE standard. There is
        // no client-side total to check, and refusing here would break the default workflow.
        when(comparisons.save(any())).thenAnswer(i -> i.getArgument(0));

        service.create("PROC-1", List.of(), null, "u");

        verify(jobDispatcher).dispatchRetrievalComparison(any(), any(), any(), any());
    }
}
