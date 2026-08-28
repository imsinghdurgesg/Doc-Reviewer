package com.app.docreview.reviewcore.comparison.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Authoritative comparisons and history, owned by review-core (§22.1). */
public interface ComparisonRepository extends MongoRepository<Comparison, String> {

    Optional<Comparison> findByPublicId(String publicId);

    /** History for one document version (§19.2). */
    List<Comparison> findByDocumentVersionIdOrderByCreatedAtDesc(String documentVersionId);

    List<Comparison> findTop50ByOrderByCreatedAtDesc();

    /**
     * One page of history, filtered and sorted in the DATABASE.
     *
     * <p>Replaces fetching a capped list and filtering in the browser. Two reasons that matters:
     * {@code findTop50} silently hid the 51st review — a reviewer searching for an older comparison
     * found nothing and had no way to tell that from "it does not exist" — and the whole payload,
     * including every finding count, crossed the wire on each page load.
     *
     * <p><strong>Filename is not searched here.</strong> It lives on the uploaded document, not on
     * the comparison, so the caller resolves matching document version ids first and passes them in.
     * A regex against a field this collection does not have would match nothing while looking
     * perfectly reasonable.
     *
     * @param publicIdTerm  regex-quoted term matched against the public id, or empty for none
     * @param documentIds   document versions whose filename matched, or empty for none
     * @param searching     false when no search term was given, which makes the two above irrelevant
     * @param states           states to include; ignored unless {@code filteringByState}
     * @param filteringByState false when no status filter was chosen.
     *
     *                         <p>A separate flag rather than testing {@code $size} on the list: an
     *                         empty array placeholder expands to {@code $size:} with no argument,
     *                         which Mongo rejects outright (error 16020) — so "no status filter"
     *                         became a 500 rather than "match everything".
     */
    @org.springframework.data.mongodb.repository.Query("""
            { $and: [
              { $or: [
                { $expr: { $eq: [ ?2, false ] } },
                { publicId: { $regex: ?0, $options: "i" } },
                { documentVersionId: { $in: ?1 } },
                { createdBy: { $regex: ?0, $options: "i" } }
              ] },
              { $or: [
                { $expr: { $eq: [ ?6, false ] } },
                { state: { $in: ?3 } }
              ] },
              { $or: [
                { $expr: { $eq: [ ?4, "" ] } },
                { standardVersionIds: ?4 }
              ] },
              { createdAt: { $gte: ?5 } },
              { $or: [
                { $expr: { $eq: [ ?7, "" ] } },
                { createdBy: ?7 }
              ] }
            ] }
            """)
    org.springframework.data.domain.Page<Comparison> search(
            String publicIdTerm,
            List<String> documentIds,
            boolean searching,
            List<String> states,
            String standardVersionId,
            java.time.Instant createdAfter,
            boolean filteringByState,
            /**
             * Restricts the result to one owner, or {@code ""} for no restriction.
             *
             * <p>An AUTHORIZATION boundary, not a filter — so the match is exact equality, never the
             * case-insensitive regex the search term uses. A regex here would let {@code a@x.edu} match
             * {@code aa@x.edu}, and a prefix match would be a way to read someone else's reviews.
             *
             * <p>A comparison with no {@code createdBy} matches no owner, so legacy rows are visible
             * only when unrestricted. That is deliberate: an unattributed review cannot be shown to a
             * reviewer as if it were theirs.
             */
            String owner,
            org.springframework.data.domain.Pageable pageable);

    /**
     * A reviewer's own recent comparisons, newest first.
     *
     * <p>The unscoped {@code findTop50ByOrderByCreatedAtDesc} is admin-only: on a shared deployment it
     * showed every reviewer the whole organization's activity.
     */
    List<Comparison> findTop50ByCreatedByOrderByCreatedAtDesc(String createdBy);
}
