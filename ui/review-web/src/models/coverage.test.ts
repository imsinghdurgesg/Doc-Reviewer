import {
  type CoverageEntry,
  type CoverageSummary,
  coverageReconciles,
  coverageStateLabel,
  isCoverageGap,
} from "./comparison";

/**
 * Reading a coverage entry. Architecture §17.6.
 *
 * The distinction these two helpers carry is what tells a reviewer what to do next: a requirement that
 * was deliberately excluded needs no action, one whose applicability is unresolved needs a scope
 * decision, and one that was never evaluated invalidates part of the conclusion. Collapsing any pair of
 * those is a wrong answer that still renders cleanly.
 */

function entry(state: CoverageEntry["state"]): CoverageEntry {
  return {
    requirementId: "CHG-17",
    requirementTitle: "Critical-service failover readiness",
    standardVersionId: "sv-1",
    state,
    reason: "reason",
  };
}

describe("coverageStateLabel", () => {
  it("names each state as a reviewer would read it", () => {
    expect(coverageStateLabel("SKIPPED")).toBe("Not applicable");
    expect(coverageStateLabel("UNCERTAIN_APPLICABILITY")).toBe("Applicability review required");
    expect(coverageStateLabel("EVALUATED")).toBe("Evaluated");
  });

  it("presents PENDING and FAILED identically", () => {
    // The reviewer's action is the same either way: this requirement is not in the conclusion. Why it
    // is missing belongs in the reason text, not in two labels that both mean "no verdict".
    expect(coverageStateLabel("PENDING")).toBe("Not evaluated");
    expect(coverageStateLabel("FAILED")).toBe("Not evaluated");
  });
});

describe("coverageReconciles", () => {
  function summary(over: Partial<CoverageSummary> = {}): CoverageSummary {
    return {
      totalRequirements: 5,
      compared: 3,
      applicabilityConfirmed: 2,
      applicabilityReviewRequired: 1,
      notApplicable: 1,
      notEvaluated: 1,
      complete: true,
      uncovered: [],
      ...over,
    };
  }

  it("accepts the worked example", () => {
    // 3 compared (2 confirmed + 1 review-required) + 1 not applicable + 1 not evaluated = 5.
    expect(coverageReconciles(summary())).toBe(true);
  });

  it("rejects a total that does not account for every requirement", () => {
    // The failure this catches: a state falling through unreported, so the parts sum to less than
    // the whole and a reviewer reads a total that silently excludes some requirements.
    expect(coverageReconciles(summary({ totalRequirements: 6 }))).toBe(false);
  });

  it("rejects children that do not sum to their parent", () => {
    // compared must equal confirmed + review-required. If it does not, one of the three numbers is
    // counting a set the other two do not describe.
    expect(coverageReconciles(summary({ applicabilityConfirmed: 3 }))).toBe(false);
  });

  it("treats the categories as disjoint, not overlapping", () => {
    // Double-counting the review-required entry as BOTH compared and not-applicable would make the
    // parts exceed the whole — the specific arithmetic error the nested presentation prevents.
    expect(coverageReconciles(summary({ notApplicable: 2 }))).toBe(false);
  });

  it("accepts a fully-confirmed review with no exceptions", () => {
    expect(coverageReconciles(summary({
      totalRequirements: 4, compared: 4, applicabilityConfirmed: 4,
      applicabilityReviewRequired: 0, notApplicable: 0, notEvaluated: 0,
    }))).toBe(true);
  });
});

describe("isCoverageGap", () => {
  it("treats only PENDING and FAILED as gaps", () => {
    expect(isCoverageGap(entry("PENDING"))).toBe(true);
    expect(isCoverageGap(entry("FAILED"))).toBe(true);
  });

  it("does not treat a decision as a gap", () => {
    // NOT_APPLICABLE and UNCERTAIN are decisions about scope, not failures to measure. Flagging them
    // as gaps would report a correctly-excluded requirement as a defect in the review.
    expect(isCoverageGap(entry("SKIPPED"))).toBe(false);
    expect(isCoverageGap(entry("UNCERTAIN_APPLICABILITY"))).toBe(false);
    expect(isCoverageGap(entry("EVALUATED"))).toBe(false);
  });

  it("keeps uncertain applicability distinct from both a gap and a clean evaluation", () => {
    // It sits between the two: compared, so not a gap; unresolved, so not finished either. If it
    // collapsed into EVALUATED the open scope question would never reach the reviewer.
    const uncertain = entry("UNCERTAIN_APPLICABILITY");
    expect(isCoverageGap(uncertain)).toBe(false);
    expect(coverageStateLabel(uncertain.state)).not.toBe("Evaluated");
  });
});
