package com.app.docreview.reviewcore.standards.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A standard: the stable identity across all its versions. Architecture §22.1.
 *
 * <p>Holds only metadata that survives versioning — key, title, owner, category. Anything that
 * changes between revisions (the file, the requirements, the index) belongs to
 * {@link StandardVersion}.
 *
 * <p><strong>No {@code @Data} and no class-level {@code @Setter}</strong> (§25). Field-level setters
 * are used for the mutable metadata only, so {@code standardKey} cannot be reassigned after
 * creation — it is the business identity that comparison history refers back to. There is also no
 * generated {@code toString()}: §25 forbids it on documents, and this one is reachable from types
 * that hold document text.
 */
@Document(collection = "standards")
@Getter
@NoArgsConstructor
public class Standard {

    @Id
    private String id;

    /** Business identity, e.g. {@code GRC-CHG}. Immutable once assigned. */
    @Indexed(unique = true)
    private String standardKey;

    @Setter private String title;
    @Setter private String description;
    @Setter private String owner;
    /**
     * The owning department. Architecture §15.1.
     *
     * <p>Named {@code businessUnit} because that is what it has always been; it IS the department
     * dimension of the Phase 1 metadata. A parallel {@code department} field would mean two places to
     * read, two to populate, and eventually two answers.
     *
     * <p>A RANKING signal, never a hard filter. Filtering candidates out by inferred department would
     * hide a whole standard family behind imperfect metadata, and the reviewer would see a clean
     * result with no indication anything was excluded (§15.1 requires the opposite).
     */
    @Setter private String businessUnit;

    /**
     * Subject area, e.g. {@code Change Management}. Contributes to the matching vector and ranking.
     */
    @Setter private String category;

    /**
     * The family this standard belongs to, e.g. {@code THIRD_PARTY_RISK}.
     *
     * <p>Distinct from {@code category}: a family groups standards that are habitually assessed
     * together, whereas a category describes one standard's subject. Two standards can share a family
     * and have different categories, and it is the family that predicts "if this one applies, that one
     * probably does too" — which is what one-to-many selection needs.
     */
    @Setter private String standardFamily;

    /**
     * The kind of document this standard governs, e.g. {@code PROCUREMENT_PROCEDURE}.
     *
     * <p>Matched against the uploaded document's own inferred type. The most direct applicability
     * signal available: a standard for procurement procedures is unlikely to govern an incident
     * runbook however similar their vocabulary.
     */
    @Setter private String documentType;

    @Setter private List<String> tags;

    private Instant createdAt;
    @Setter private Instant updatedAt;
    private String createdBy;

    public Standard(String standardKey, String title, String owner, String createdBy) {
        this.standardKey = standardKey;
        this.title = title;
        this.owner = owner;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        // Non-null so a later append does not NPE, and so Mongo stores [] rather than missing.
        this.tags = List.of();
    }
}
