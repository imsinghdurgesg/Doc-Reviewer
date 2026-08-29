package com.app.docreview.retrievalcomparison.comparison;

import com.app.docreview.contracts.comparison.Finding;
import com.app.docreview.contracts.enums.ComparisonStatus;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Findings stored before a field existed must still load. Architecture §19.2.
 *
 * <p><strong>The production failure this prevents.</strong> {@code Finding.conclusionEligible} was
 * added as a primitive {@code boolean}. Every stored finding predates it, so Mongo supplied null, a
 * primitive cannot accept null, and construction threw {@code "Parameter conclusionEligible must not be
 * null"} — for EVERY historical finding. History went blank for all comparisons, not only for ones
 * touched by the new feature. Production holds 640 such documents.
 *
 * <p><strong>Why this test goes through the real converter.</strong> A unit test that calls the
 * canonical constructor with nulls proves the record tolerates them; it does NOT prove Spring Data
 * picks that constructor or maps an absent BSON key to null rather than failing earlier. The defect
 * lived in the mapping layer, so the test has to exercise the mapping layer.
 *
 * <p>The document below is deliberately built key-by-key in the pre-change shape rather than by
 * serialising a current {@code Finding} and deleting fields — a round trip through today's writer
 * would encode today's assumptions and could not have caught this.
 */
class LegacyFindingDeserializationTest {

    private static MappingMongoConverter converter() {
        var context = new MongoMappingContext();
        // Records are mapped through their canonical constructor; no entity instantiation shortcuts.
        context.setSimpleTypeHolder(
                org.springframework.data.mapping.model.SimpleTypeHolder.DEFAULT);
        context.afterPropertiesSet();
        DbRefResolver noRefs = NoOpDbRefResolver.INSTANCE;
        var converter = new MappingMongoConverter(noRefs, context);
        converter.afterPropertiesSet();
        return converter;
    }

    /**
     * A finding exactly as production stored it: no {@code conclusionEligible}, no
     * {@code evaluationLimitation}, no {@code unmappedStandardObligations}, no {@code retriedAs}.
     *
     * <p>Field values mirror a real row from {@code atomic_comparison_results} — the one whose failure
     * produced the blank History — with document text omitted (§35).
     */
    private static Document legacyFinding() {
        var document = new Document();
        document.put("_id", "FND-5718fb35-b60");
        document.put("findingId", "FND-5718fb35-b60");
        document.put("comparisonId", "CMP-90fa2d34-f43");
        document.put("standardVersionId", "6a886c9afc6ee17f0bca62f1");
        document.put("requirementSetVersion", "REQSET-V1");
        document.put("requirementId", "1-REQ-1");
        document.put("parentControlId", "1");
        document.put("requirementTitle", "Not applicable marking conditions");
        document.put("status", "MISSING");
        document.put("gapType", null);
        document.put("severity", "MEDIUM");
        document.put("standardSpanIds", List.of("SPAN-428a81e4-35-2"));
        document.put("processSpanIds", List.of());
        document.put("nearestProcessSpanIds", List.of("SPAN-ac58570f-122-0"));
        document.put("clauseResults", List.of());
        document.put("reason", "The searched process evidence does not address this requirement.");
        document.put("recommendedAction", "Add a documented rule.");
        document.put("confidence", 0.97);
        document.put("needsHumanReview", false);
        document.put("applicabilityStatus", "APPLICABLE");
        document.put("applicabilityReason", null);
        document.put("applicabilityDecidedBy", "APPLICABILITY-RULES-V3");
        return document;
    }

    @Test
    @DisplayName("a finding stored without conclusionEligible loads through the Mongo converter")
    void legacyDocumentLoads() {
        // THE regression. As a primitive this threw here, and every History request failed.
        var finding = converter().read(Finding.class, legacyFinding());

        assertThat(finding).isNotNull();
        assertThat(finding.findingId()).isEqualTo("FND-5718fb35-b60");
        assertThat(finding.status()).isEqualTo(ComparisonStatus.MISSING);
    }

    @Test
    @DisplayName("the absent field reads as conclusion-eligible, so legacy findings still count")
    void absentFieldDefaultsToEligible() {
        // A finding written before limitations existed was produced under rules where every verdict
        // counted. Defaulting it to NOT eligible would silently drop 640 production findings out of
        // their own comparisons' conclusions — a quieter failure than the crash, and worse.
        var finding = converter().read(Finding.class, legacyFinding());

        assertThat(finding.countsTowardConclusion()).isTrue();
        assertThat(finding.evaluationLimitation()).isNull();
        assertThat(finding.extractionIncomplete()).isFalse();
        assertThat(finding.unmappedStandardObligations()).isEmpty();
    }

    @Test
    @DisplayName("legacy applicability and provenance survive unchanged")
    void legacyFieldsAreNotReinterpreted() {
        // §19.2: loading an old finding must not re-explain it. The applicability decision and the
        // resolver that made it are what a reviewer reopening a six-month-old comparison relies on.
        var finding = converter().read(Finding.class, legacyFinding());

        assertThat(finding.applicability())
                .isEqualTo(com.app.docreview.contracts.enums.ApplicabilityStatus.APPLICABLE);
        assertThat(finding.applicabilityDecidedBy()).isEqualTo("APPLICABILITY-RULES-V3");
        assertThat(finding.retriedAs())
                .as("an absent retry stamp means the original run, not an unknown one")
                .isNull();
        assertThat(finding.actionable())
                .as("a historical gap must not stop being actionable")
                .isTrue();
    }

    @Test
    @DisplayName("a stored eligible flag is overridden by a limitation")
    void limitationWinsOverStoredFlag() {
        // Defence in depth for the other direction: a row carrying both must not count. Otherwise a
        // data-level mistake returns an unassessed obligation to the conclusion.
        var document = legacyFinding();
        document.put("conclusionEligible", true);
        document.put("evaluationLimitation", "STANDARD_REQUIREMENT_EXTRACTION_INCOMPLETE");

        var finding = converter().read(Finding.class, document);

        assertThat(finding.extractionIncomplete()).isTrue();
        assertThat(finding.countsTowardConclusion()).isFalse();
    }

    @Test
    @DisplayName("conclusionEligible is a boxed type, so an absent key cannot fail construction")
    void fieldIsBoxedNotPrimitive() {
        // Asserted structurally as well as behaviourally. Someone "simplifying" this back to a
        // primitive would break every stored finding again, and the behavioural tests above would
        // fail with a confusing mapping error rather than naming the cause.
        var component = java.util.Arrays.stream(Finding.class.getRecordComponents())
                .filter(c -> c.getName().equals("conclusionEligible"))
                .findFirst()
                .orElseThrow();

        assertThat(component.getType())
                .as("a primitive boolean cannot accept the null Mongo supplies for an absent key")
                .isEqualTo(Boolean.class);
    }
}
