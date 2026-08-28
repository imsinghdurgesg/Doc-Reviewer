import { parseUnevaluated } from "./comparison";

/**
 * Splitting an unevaluated-requirement entry. Architecture §18.4.
 *
 * The wire form is `requirementId: reason`, and the id must survive intact — it is what a reviewer
 * uses to find the affected control in the standard. A mis-split that swallows the id turns a
 * checkable limitation back into an unexplained one.
 */
describe("parseUnevaluated", () => {
  it("splits an id from its reason", () => {
    expect(parseUnevaluated("CHG-04-2: PARTIAL requires at least one unsatisfied clause")).toEqual({
      requirementId: "CHG-04-2",
      reason: "PARTIAL requires at least one unsatisfied clause",
    });
  });

  it("keeps a reason that itself contains a colon", () => {
    // Splits on the FIRST ": " only; an exception message often carries more.
    expect(parseUnevaluated("CHG-07: IllegalStateException: no candidates")).toEqual({
      requirementId: "CHG-07",
      reason: "IllegalStateException: no candidates",
    });
  });

  it("treats a bare id with no reason as an id", () => {
    // Never drop the entry: an unexplained failure is still a failure the reviewer must see.
    expect(parseUnevaluated("CHG-09")).toEqual({ requirementId: "CHG-09", reason: "" });
  });

  it("trims surrounding whitespace", () => {
    expect(parseUnevaluated("  CHG-11 :  timed out  ")).toEqual({
      requirementId: "CHG-11",
      reason: "timed out",
    });
  });
});
