package com.app.docreview.reviewcore.standards;

import com.app.docreview.reviewcore.standards.application.ConditionGovernanceClient;
import com.app.docreview.reviewcore.standards.web.StandardAdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The conditions endpoint resolves its own requirement set. Architecture §12.9, §22.3.
 *
 * <p><strong>The bug this pins.</strong> The endpoint used to take {@code requirementSetVersion} from the
 * caller, and the browser supplied it from review-core's denormalised copy on the version record. That
 * copy is only written when review-core drives an extraction — so after re-extractions run against the
 * worker it read {@code REQSET-V1} for all ten versions while {@code REQSET-V3} was live.
 *
 * <p>The result was the worst kind of wrong: the request succeeded, returned an empty list, and the page
 * rendered "this requirement set states no applicability conditions" — a stale pointer presenting itself
 * as a clean review of 53 conditions, 27 of which a person had reclassified.
 */
class ConditionSetResolutionTest {

    private static final String VERSION = "6a8d6f379b065e2acc9ad5e1";

    private final ConditionGovernanceClient client = mock(ConditionGovernanceClient.class);
    private final StandardAdminController controller =
            new StandardAdminController(null, client);

    private static ConditionGovernanceClient.ConditionRow row() {
        return new ConditionGovernanceClient.ConditionRow(
                "CHG-01-2", "Emergency changes", "SCOPE_GATE", "CONDITIONAL_OBLIGATION",
                "REVIEWER_OVERRIDE", false, "notes", "CONDROLE-REVIEW-V1", true);
    }

    @Test
    @DisplayName("the set is resolved from the owning service, not taken from the caller")
    void resolvesTheCurrentSet() {
        when(client.currentSet(VERSION)).thenReturn(
                new ConditionGovernanceClient.CurrentSet("REQSET-V3", "REQ-EXTRACT-V3", 72));
        when(client.conditions(VERSION, "REQSET-V3")).thenReturn(List.of(row()));

        var response = controller.conditions(VERSION);

        assertThat(response.requirementSetVersion()).isEqualTo("REQSET-V3");
        assertThat(response.promptVersion()).isEqualTo("REQ-EXTRACT-V3");
        assertThat(response.conditions()).hasSize(1);
        // The resolved set, never a caller-supplied one.
        verify(client).conditions(VERSION, "REQSET-V3");
    }

    @Test
    @DisplayName("no comparable set is reported as such, not as an empty condition list")
    void distinguishesNoSetFromNoConditions() {
        when(client.currentSet(VERSION)).thenReturn(null);

        var response = controller.conditions(VERSION);

        // A null set version is what lets the page say "extraction has not run" instead of "this
        // standard states no conditions". Collapsing the two is how the original bug hid.
        assertThat(response.requirementSetVersion()).isNull();
        assertThat(response.conditions()).isEmpty();
        // And it must not query conditions for a set it could not resolve.
        verify(client, never()).conditions(any(), any());
    }

    @Test
    @DisplayName("a resolved set with no conditions still reports which set it was")
    void reportsTheSetEvenWhenEmpty() {
        when(client.currentSet(VERSION)).thenReturn(
                new ConditionGovernanceClient.CurrentSet("REQSET-V3", "REQ-EXTRACT-V3", 72));
        when(client.conditions(eq(VERSION), eq("REQSET-V3"))).thenReturn(List.of());

        var response = controller.conditions(VERSION);

        assertThat(response.requirementSetVersion()).isEqualTo("REQSET-V3");
        assertThat(response.conditions()).isEmpty();
    }

    @Test
    @DisplayName("the endpoint takes no requirementSetVersion path variable")
    void endpointDoesNotAcceptASetFromTheCaller() throws IOException {
        // Structural: as long as the path can carry a set version, a caller can supply a stale one.
        // Removing the parameter is what makes the failure impossible rather than merely unlikely.
        var source = Files.readString(Path.of(
                "src/main/java/com/app/docreview/reviewcore/standards/web/"
                        + "StandardAdminController.java"), StandardCharsets.UTF_8);

        assertThat(source)
                .as("the conditions path must not accept a caller-supplied requirement set")
                .doesNotContain("requirement-sets/{requirementSetVersion}/conditions");
        assertThat(source).contains("/versions/{standardVersionId}/conditions");
    }
}
