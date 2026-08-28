import type { ComparisonSummary } from "@/models/comparison";

/**
 * Search, filter and sort for the history list. Architecture §19.2.
 *
 * <p>These describe the query; the SERVER applies it. Filtering in the browser required fetching the
 * whole collection first, which meant a capped list — and a cap silently hides older reviews, so a
 * search for last quarter's comparison returned nothing and looked exactly like "it does not exist".
 *
 * <p>{@link filterHistory} is retained for tests and as the reference definition of each filter's
 * meaning, but the page no longer calls it.
 */

export type StatusFilter = "ALL" | "MISMATCHES" | "CLEAN" | "RUNNING" | "FAILED";
export type DateFilter = "ALL" | "TODAY" | "WEEK" | "MONTH";
export type SortOrder = "NEWEST" | "OLDEST" | "MOST_FINDINGS";

export interface HistoryFilters {
  search: string;
  status: StatusFilter;
  date: DateFilter;
  /** Standard title, or "ALL". */
  reference: string;
  sort: SortOrder;
}

export const DEFAULT_FILTERS: HistoryFilters = {
  search: "",
  status: "ALL",
  date: "ALL",
  reference: "ALL",
  sort: "NEWEST",
};

/** Terminal states that produced a result, per §19.2. */
const COMPLETED = new Set(["COMPLETED", "PARTIALLY_COMPLETED"]);

function matchesStatus(row: ComparisonSummary, status: StatusFilter): boolean {
  switch (status) {
    case "ALL":
      return true;
    case "FAILED":
      return row.status === "FAILED";
    case "RUNNING":
      // Anything not terminal. A review still in progress has no meaningful finding count yet, so
      // grouping it with "clean" would misreport work that has not finished.
      return !COMPLETED.has(row.status) && row.status !== "FAILED";
    case "MISMATCHES":
      return COMPLETED.has(row.status) && (row.actionableFindingCount ?? 0) > 0;
    case "CLEAN":
      return COMPLETED.has(row.status) && (row.actionableFindingCount ?? 0) === 0;
  }
}

function matchesDate(row: ComparisonSummary, filter: DateFilter, now: number): boolean {
  if (filter === "ALL") {
    return true;
  }
  const created = Date.parse(row.createdAt);
  if (Number.isNaN(created)) {
    // An unparseable timestamp is kept rather than hidden: dropping a row because its date could not
    // be read makes a review disappear with no explanation.
    return true;
  }
  const days = filter === "TODAY" ? 1 : filter === "WEEK" ? 7 : 30;
  return now - created <= days * 24 * 60 * 60 * 1000;
}

function matchesSearch(row: ComparisonSummary, search: string): boolean {
  const term = search.trim().toLowerCase();
  if (!term) {
    return true;
  }
  // Filename, comparison id and standard title. The id matters because it is what a reviewer quotes
  // in a ticket, and it is the only field guaranteed unique.
  return [row.filename, row.publicId, row.topStandardTitle]
    .filter((value): value is string => Boolean(value))
    .some((value) => value.toLowerCase().includes(term));
}

export function filterHistory(
  rows: readonly ComparisonSummary[],
  filters: HistoryFilters,
  now: number = Date.now(),
): ComparisonSummary[] {
  const filtered = rows.filter(
    (row) =>
      matchesSearch(row, filters.search) &&
      matchesStatus(row, filters.status) &&
      matchesDate(row, filters.date, now) &&
      (filters.reference === "ALL" || row.topStandardTitle === filters.reference),
  );

  return [...filtered].sort((a, b) => {
    if (filters.sort === "MOST_FINDINGS") {
      return (b.actionableFindingCount ?? 0) - (a.actionableFindingCount ?? 0);
    }
    const left = Date.parse(a.createdAt) || 0;
    const right = Date.parse(b.createdAt) || 0;
    return filters.sort === "OLDEST" ? left - right : right - left;
  });
}

/**
 * Standard titles present in the data, for the reference dropdown.
 *
 * <p>Derived rather than fetched: offering a standard nothing was ever compared against produces a
 * filter that always returns nothing, which reads as a broken list.
 */
export function referenceOptions(rows: readonly ComparisonSummary[]): string[] {
  const titles = new Set<string>();
  rows.forEach((row) => {
    if (row.topStandardTitle) {
      titles.add(row.topStandardTitle);
    }
  });
  return [...titles].sort((a, b) => a.localeCompare(b));
}

/** Days for a date filter, or null for all time. Mirrors the server's `days` parameter. */
export function daysFor(filter: DateFilter): number | null {
  switch (filter) {
    case "TODAY":
      return 1;
    case "WEEK":
      return 7;
    case "MONTH":
      return 30;
    default:
      return null;
  }
}

export function isDefault(filters: HistoryFilters): boolean {
  return (
    filters.search === "" &&
    filters.status === "ALL" &&
    filters.date === "ALL" &&
    filters.reference === "ALL" &&
    filters.sort === "NEWEST"
  );
}
