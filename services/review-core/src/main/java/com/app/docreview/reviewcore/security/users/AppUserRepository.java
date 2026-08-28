package com.app.docreview.reviewcore.security.users;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

/** Persistence for {@link AppUser}. Architecture §33. */
public interface AppUserRepository extends MongoRepository<AppUser, String> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * One page of users, optionally narrowed by a search term.
     *
     * <p>The term matches email and display name, case-insensitively. The {@code searching} flag makes
     * "no filter" explicit rather than relying on a pattern that happens to match everything — see
     * ComparisonRepository for why an empty-array placeholder is avoided.
     */
    @Query("""
            { $or: [
              { $expr: { $eq: [ ?1, false ] } },
              { email: { $regex: ?0, $options: "i" } },
              { displayName: { $regex: ?0, $options: "i" } }
            ] }
            """)
    Page<AppUser> search(String term, boolean searching, Pageable pageable);

    /**
     * How many ACTIVE administrators exist.
     *
     * <p>Used to refuse the change that removes the last one. Without it, a single edit can lock every
     * administrator out of the application, and the only recovery is editing the database directly.
     */
    long countByRolesContainingAndStatus(com.app.docreview.reviewcore.security.Role role,
                                         AppUser.Status status);
}
