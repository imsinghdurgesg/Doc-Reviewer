package com.app.docreview.contracts.page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of any collection. Architecture §7.4.
 *
 * <p>Generic rather than per-collection so history, standards, and anything else paginated later
 * share one wire shape — a client that can render a page of comparisons can render a page of
 * standards without a second set of types, and a UI pager component works against all of them.
 *
 * <p>Deliberately NOT Spring Data's {@code Page}. That type serialises with a large, unstable
 * envelope (`pageable`, `sort`, `numberOfElements`, `first`, `last`, `empty`) whose shape has changed
 * across Boot versions and which leaks the persistence library into the browser contract. §7.4 keeps
 * the wire format the platform's own.
 *
 * @param content       the rows on this page
 * @param totalElements rows matching the query across all pages
 * @param page          zero-based index of this page
 * @param size          requested page size, which may exceed {@code content.size()} on the last page
 * @param totalPages    total pages at this size, 0 when nothing matched
 * @param exactCount    whether {@code totalElements} reflects EVERY applied filter.
 *
 *                      <p>False when a filter could not be expressed in the query and was applied to
 *                      the fetched page instead — the total then counts rows this page dropped.
 *                      Carrying the caveat rather than hiding it lets the UI say "showing 8" instead
 *                      of asserting a total it knows is wrong.
 */
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int page,
        int size,
        int totalPages,
        boolean exactCount
) {

    /** An exact page, for the common case where the database applied every filter. */
    public static <T> PageResponse<T> of(List<T> content, long totalElements, int page, int size,
                                         int totalPages) {
        return new PageResponse<>(content, totalElements, page, size, totalPages, true);
    }

    public static <T> PageResponse<T> empty(int size) {
        return new PageResponse<>(List.of(), 0, 0, size, 0, true);
    }

    /**
     * Maps the rows, preserving the pagination envelope.
     *
     * <p>The usual shape at a controller boundary: a repository returns a page of entities and the
     * response needs a page of DTOs, with the counts unchanged.
     */
    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(
                content.stream().map(mapper).toList(),
                totalElements, page, size, totalPages, exactCount);
    }
}
