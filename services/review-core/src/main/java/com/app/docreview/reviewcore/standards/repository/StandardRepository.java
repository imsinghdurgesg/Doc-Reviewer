package com.app.docreview.reviewcore.standards.repository;

import com.app.docreview.reviewcore.standards.domain.Standard;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Standards metadata. Owned exclusively by review-core (§22.1). */
public interface StandardRepository extends MongoRepository<Standard, String> {

    Optional<Standard> findByStandardKey(String standardKey);

    boolean existsByStandardKey(String standardKey);

    /**
     * One page of standards, searched and sorted in the DATABASE.
     *
     * <p>Search covers title, key and owner — the three things an administrator recognises a
     * standard by. Case-insensitive and unanchored: "access" should find "Access Control Standard",
     * not only a title that begins with it.
     *
     * @param term regex-quoted search term, or empty to match everything
     */
    @org.springframework.data.mongodb.repository.Query("""
            { $or: [
              { $expr: { $eq: [ ?0, "" ] } },
              { title:       { $regex: ?0, $options: "i" } },
              { standardKey: { $regex: ?0, $options: "i" } },
              { owner:       { $regex: ?0, $options: "i" } }
            ] }
            """)
    org.springframework.data.domain.Page<Standard> search(
            String term, org.springframework.data.domain.Pageable pageable);
}
