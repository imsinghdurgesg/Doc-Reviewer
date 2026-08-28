/**
 * One page of any collection. Mirrors `PageResponse<T>` in platform-contracts.
 *
 * Generic so history, standards and anything paginated later share one shape — the pager component
 * and the query helper below work against all of them without a per-collection variant.
 */
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  /** Zero-based. */
  page: number;
  size: number;
  totalPages: number;
  /**
   * Whether `totalElements` reflects every applied filter.
   *
   * False when the server applied a filter to the fetched page rather than in the query, so the
   * total counts rows this page dropped. The UI must then say "showing N" rather than asserting a
   * total it knows is wrong.
   */
  exactCount: boolean;
}

export function emptyPage<T>(size = 20): PageResponse<T> {
  return { content: [], totalElements: 0, page: 0, size, totalPages: 0, exactCount: true };
}

/**
 * Builds a query string, omitting empty values.
 *
 * Omitting rather than sending blanks keeps the URL — and therefore the react-query cache key —
 * stable: `?search=&status=ALL` and `?status=ALL` describe the same request but would otherwise be
 * cached separately and refetched on every filter reset.
 */
export function toQuery(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== "") {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}
