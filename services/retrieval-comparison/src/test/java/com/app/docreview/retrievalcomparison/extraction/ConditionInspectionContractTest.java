package com.app.docreview.retrievalcomparison.extraction;

import com.app.docreview.contracts.enums.ConditionRole;
import com.app.docreview.contracts.enums.ConditionRoleSource;
import com.app.docreview.retrievalcomparison.extraction.web.RequirementInspectionController.ConditionInspection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The condition-inspection wire contract. Architecture §12.5, §19.2.
 *
 * <p><strong>Why serialise rather than just call the accessor.</strong> {@code reclassified} began as a
 * derived method on the record, so it compiled, unit-tested green, and was absent from every JSON
 * response — Jackson serialises record COMPONENTS, not computed accessors. The admin filter keyed on it
 * silently matched nothing, which looks exactly like "review agreed with the extraction everywhere":
 * the most reassuring possible rendering of a broken response.
 *
 * <p>So these assert the JSON, not the object.
 */
class ConditionInspectionContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ConditionInspection row(ConditionRole extracted, ConditionRole effective,
                                           ConditionRoleSource source, boolean canExclude) {
        return new ConditionInspection("CHG-14-2", "Emergency changes",
                extracted, effective, source, canExclude, "reviewer rationale", "CONDROLE-REVIEW-V1",
                "Emergency changes must be reviewed within one business day.",
                java.util.List.of("SPAN-1"));
    }

    @Test
    @DisplayName("every field an administrator reads survives serialisation")
    void allFieldsAreOnTheWire() throws Exception {
        var json = mapper.readTree(mapper.writeValueAsString(
                row(ConditionRole.SCOPE_GATE, ConditionRole.CONDITIONAL_OBLIGATION,
                        ConditionRoleSource.REVIEWER_OVERRIDE, false)));

        assertThat(json.has("requirementId")).isTrue();
        assertThat(json.has("conditionText")).isTrue();
        // BOTH roles. Dropping the extracted one would hide the disagreement, which is the only
        // measure of extraction quality there is.
        assertThat(json.get("extractedRole").asText()).isEqualTo("SCOPE_GATE");
        assertThat(json.get("effectiveRole").asText()).isEqualTo("CONDITIONAL_OBLIGATION");
        assertThat(json.get("source").asText()).isEqualTo("REVIEWER_OVERRIDE");
        assertThat(json.get("canDriveNotApplicable").asBoolean()).isFalse();
        assertThat(json.get("overrideBatchId").asText()).isEqualTo("CONDROLE-REVIEW-V1");
        assertThat(json.has("reviewerNotes")).isTrue();
        // The clause and its span. A condition alone is often unjudgeable — the extractor truncated
        // "When the change is a material security or privacy change" to "When it is" — so a reviewer
        // needs the surrounding requirement and a span to check it against (§16.5).
        assertThat(json.get("requirementText").asText())
                .isEqualTo("Emergency changes must be reviewed within one business day.");
        assertThat(json.get("conditionSourceSpanIds").isArray()).isTrue();
        assertThat(json.get("conditionSourceSpanIds")).hasSize(1);
    }

    @Test
    @DisplayName("reclassified is present in JSON, not merely computable")
    void reclassifiedIsSerialised() throws Exception {
        var changed = mapper.readTree(mapper.writeValueAsString(
                row(ConditionRole.SCOPE_GATE, ConditionRole.CONDITIONAL_OBLIGATION,
                        ConditionRoleSource.REVIEWER_OVERRIDE, false)));
        var agreed = mapper.readTree(mapper.writeValueAsString(
                row(ConditionRole.CONDITIONAL_OBLIGATION, ConditionRole.CONDITIONAL_OBLIGATION,
                        ConditionRoleSource.REVIEWER_OVERRIDE, false)));

        assertThat(changed.has("reclassified"))
                .as("a client filtering on an absent field shows nothing and reports success")
                .isTrue();
        assertThat(changed.get("reclassified").asBoolean()).isTrue();
        assertThat(agreed.get("reclassified").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("role and exclusion permission serialise independently")
    void permissionIsNotDerivedFromRole() throws Exception {
        // A confirmed SCOPE_GATE that nobody authorised. If the wire form collapsed these two into
        // one flag, the admin page would show an exclusion power that does not exist — and the
        // separation is the safety layer, so it has to survive the boundary.
        var unauthorised = mapper.readTree(mapper.writeValueAsString(
                row(ConditionRole.SCOPE_GATE, ConditionRole.SCOPE_GATE,
                        ConditionRoleSource.REVIEWER_OVERRIDE, false)));

        assertThat(unauthorised.get("effectiveRole").asText()).isEqualTo("SCOPE_GATE");
        assertThat(unauthorised.get("canDriveNotApplicable").asBoolean()).isFalse();
        assertThat(unauthorised.get("reclassified").asBoolean()).isFalse();
    }
}
