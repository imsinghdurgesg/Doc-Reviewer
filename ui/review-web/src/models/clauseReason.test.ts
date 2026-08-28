import { hasClauseSpecificDetail, isClauseSpecificReason } from "./comparison";
import type { AtomicClauseResult } from "./comparison";

/**
 * Suppressing inherited clause reasons. Architecture §18.5, §19.2.
 *
 * Schema V1 returned ONE reason per requirement and it was copied onto every clause, so a
 * three-clause finding rendered the same multi-clause paragraph three times — each copy discussing
 * all three clauses beside a single clause's status chip. Stored results still carry the copy and
 * must not be rewritten, so the duplication is detected at read time.
 *
 * The rule under test: evidence and explanation must be *attributed* to a clause, never inherited.
 * Two clauses sharing a genuine explanation is allowed; every clause sharing one string is not.
 */
describe("clause reason attribution", () => {
  const clause = (
    clauseId: string,
    reason: string | null,
  ): AtomicClauseResult => ({
    clauseId,
    status: "ALIGNED",
    gapType: null,
    processSpanIds: [],
    reason,
  });

  describe("inherited reasons are suppressed", () => {
    it("hides a reason shared by every clause in a multi-clause finding", () => {
      // The exact V1 shape observed in CMP-7a66ecf1-13b.
      const shared = "2.1-IMPL-1 is satisfied because… 2.1-IMPL-2 is satisfied because…";
      const all = [
        clause("2.1-IMPL-1", shared),
        clause("2.1-IMPL-2", shared),
        clause("2.1-IMPL-3", shared),
      ];

      expect(all.every((c) => !isClauseSpecificReason(c, all))).toBe(true);
      expect(hasClauseSpecificDetail(all)).toBe(false);
    });

    it("treats a null reason as not clause-specific", () => {
      // New V1 results leave the field null rather than copying the parent's.
      const all = [clause("A", null), clause("B", null)];

      expect(hasClauseSpecificDetail(all)).toBe(false);
    });
  });

  describe("genuine clause reasons are shown", () => {
    it("shows reasons that differ across clauses", () => {
      const all = [
        clause("A", "The process assigns implementation to an authorised engineer."),
        clause("B", "The process requires activities to be recorded in the ticket."),
      ];

      expect(all.every((c) => isClauseSpecificReason(c, all))).toBe(true);
      expect(hasClauseSpecificDetail(all)).toBe(true);
    });

    it("allows two clauses to share a reason when a third differs", () => {
      // Legitimately identical explanations must NOT be treated as inheritance — the signature of
      // inheritance is every clause sharing one string, not any two of them.
      const all = [
        clause("A", "Not addressed by the procedure."),
        clause("B", "Not addressed by the procedure."),
        clause("C", "Covered by the release checklist."),
      ];

      expect(hasClauseSpecificDetail(all)).toBe(true);
      expect(isClauseSpecificReason(all[0]!, all)).toBe(true);
    });

    it("shows the reason on a single-clause finding", () => {
      // With one clause there is no inheritance to detect, and the requirement's reason IS the
      // clause's — showing it once here is correct rather than misleading.
      const all = [clause("A", "The control is fully reflected in the procedure.")];

      expect(isClauseSpecificReason(all[0]!, all)).toBe(true);
    });
  });

  describe("degenerate input", () => {
    it("handles an empty clause list", () => {
      expect(hasClauseSpecificDetail([])).toBe(false);
    });

    it("ignores blank reasons when judging distinctness", () => {
      const all = [clause("A", ""), clause("B", "")];

      expect(hasClauseSpecificDetail(all)).toBe(false);
    });
  });
});
