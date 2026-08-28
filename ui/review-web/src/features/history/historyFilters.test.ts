import {
  DEFAULT_FILTERS,
  filterHistory,
  referenceOptions,
  type HistoryFilters,
} from "@/features/history/historyFilters";
import type { ComparisonSummary } from "@/models/comparison";

/**
 * History search, filtering and sorting. Architecture §19.2.
 *
 * <p>The distinction these pin down is between a review that found nothing and one that has not
 * finished. Both show zero findings, and collapsing them tells a reviewer their document is clean
 * when the comparison is still running.
 */

function row(overrides: Partial<ComparisonSummary> = {}): ComparisonSummary {
  return {
    publicId: "CMP-0001",
    documentVersionId: "PROC-1",
    filename: "expense-process.txt",
    status: "COMPLETED",
    findingCount: 10,
    actionableFindingCount: 0,
    topStandardTitle: "Expense Reimbursement Standard",
    topStandardKey: "FIN-EXP-2025",
    reviewer: "reviewer@stanford.edu",
    createdAt: "2026-08-20T10:00:00Z",
    completedAt: "2026-08-20T10:05:00Z",
    ...overrides,
  };
}

function filters(overrides: Partial<HistoryFilters> = {}): HistoryFilters {
  return { ...DEFAULT_FILTERS, ...overrides };
}

describe("filterHistory", () => {
  it("returns everything under the default filters", () => {
    const rows = [row(), row({ publicId: "CMP-0002" })];

    expect(filterHistory(rows, filters())).toHaveLength(2);
  });

  it("searches filename, comparison id and standard title", () => {
    const rows = [
      row({ publicId: "CMP-AAA", filename: "expense-process.txt" }),
      row({ publicId: "CMP-BBB", filename: "change-management.md",
            topStandardTitle: "Technology Change Management Standard" }),
    ];

    expect(filterHistory(rows, filters({ search: "expense" }))).toHaveLength(1);
    // The id is what a reviewer quotes in a ticket, and the only field guaranteed unique.
    expect(filterHistory(rows, filters({ search: "CMP-BBB" }))[0]?.filename)
      .toBe("change-management.md");
    expect(filterHistory(rows, filters({ search: "Technology" }))).toHaveLength(1);
  });

  it("searches case-insensitively", () => {
    const rows = [row({ filename: "Expense-Process.txt" })];

    expect(filterHistory(rows, filters({ search: "EXPENSE" }))).toHaveLength(1);
  });

  it("separates mismatches from clean reviews", () => {
    const rows = [
      row({ publicId: "A", actionableFindingCount: 3 }),
      row({ publicId: "B", actionableFindingCount: 0 }),
    ];

    expect(filterHistory(rows, filters({ status: "MISMATCHES" }))[0]?.publicId).toBe("A");
    expect(filterHistory(rows, filters({ status: "CLEAN" }))[0]?.publicId).toBe("B");
  });

  it("does not count a running review as clean", () => {
    // The important one. A comparison in progress reports zero findings because it has not finished,
    // not because the document complies — reporting it as clean is the most misleading thing this
    // list can do.
    const rows = [row({ status: "RUNNING", actionableFindingCount: 0 })];

    expect(filterHistory(rows, filters({ status: "CLEAN" }))).toHaveLength(0);
    expect(filterHistory(rows, filters({ status: "RUNNING" }))).toHaveLength(1);
  });

  it("treats a partially completed review as a real result", () => {
    // §18.4: three failures out of 200 requirements is a result with 197 valid findings, not a
    // failure — so it belongs in the mismatch/clean split rather than with running reviews.
    const rows = [row({ status: "PARTIALLY_COMPLETED", actionableFindingCount: 2 })];

    expect(filterHistory(rows, filters({ status: "MISMATCHES" }))).toHaveLength(1);
  });

  it("filters by age relative to now", () => {
    const now = Date.parse("2026-08-20T12:00:00Z");
    const rows = [
      row({ publicId: "TODAY", createdAt: "2026-08-20T09:00:00Z" }),
      row({ publicId: "OLD", createdAt: "2026-06-01T09:00:00Z" }),
    ];

    expect(filterHistory(rows, filters({ date: "TODAY" }), now)).toHaveLength(1);
    expect(filterHistory(rows, filters({ date: "MONTH" }), now)[0]?.publicId).toBe("TODAY");
    expect(filterHistory(rows, filters({ date: "ALL" }), now)).toHaveLength(2);
  });

  it("keeps a row whose timestamp cannot be parsed", () => {
    // Hiding it would make a review vanish with no explanation.
    const rows = [row({ createdAt: "not-a-date" })];

    expect(filterHistory(rows, filters({ date: "TODAY" }))).toHaveLength(1);
  });

  it("filters by standard", () => {
    const rows = [
      row({ publicId: "A", topStandardTitle: "Expense Reimbursement Standard" }),
      row({ publicId: "B", topStandardTitle: "Technology Change Management Standard" }),
    ];

    const result = filterHistory(rows, filters({ reference: "Expense Reimbursement Standard" }));

    expect(result).toHaveLength(1);
    expect(result[0]?.publicId).toBe("A");
  });

  it("sorts newest first by default, and oldest or by findings on request", () => {
    const rows = [
      row({ publicId: "OLD", createdAt: "2026-08-01T10:00:00Z", actionableFindingCount: 9 }),
      row({ publicId: "NEW", createdAt: "2026-08-20T10:00:00Z", actionableFindingCount: 1 }),
    ];

    expect(filterHistory(rows, filters())[0]?.publicId).toBe("NEW");
    expect(filterHistory(rows, filters({ sort: "OLDEST" }))[0]?.publicId).toBe("OLD");
    expect(filterHistory(rows, filters({ sort: "MOST_FINDINGS" }))[0]?.publicId).toBe("OLD");
  });

  it("does not mutate the input array", () => {
    // Sorting in place would reorder the query cache and make the list jump under the reader.
    const rows = [
      row({ publicId: "OLD", createdAt: "2026-08-01T10:00:00Z" }),
      row({ publicId: "NEW", createdAt: "2026-08-20T10:00:00Z" }),
    ];

    filterHistory(rows, filters());

    expect(rows[0]?.publicId).toBe("OLD");
  });
});

describe("referenceOptions", () => {
  it("lists each standard once, alphabetically", () => {
    const rows = [
      row({ topStandardTitle: "Technology Change Management Standard" }),
      row({ topStandardTitle: "Expense Reimbursement Standard" }),
      row({ topStandardTitle: "Technology Change Management Standard" }),
    ];

    expect(referenceOptions(rows)).toEqual([
      "Expense Reimbursement Standard",
      "Technology Change Management Standard",
    ]);
  });

  it("omits reviews with no resolved standard", () => {
    // Offering a blank option would produce a filter that always returns nothing.
    const rows = [row({ topStandardTitle: null })];

    expect(referenceOptions(rows)).toEqual([]);
  });
});
