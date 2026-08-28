import {
  type ApplicabilityStatus,
  type Finding,
  findingApplicability,
  isGovernedDecision,
  needsScopeReview,
} from "./comparison";

/**
 * Reading a finding's applicability. Architecture §12.5, §17.6.
 *
 * These three helpers decide what a reviewer sees, so each failure mode is a specific wrong belief:
 * that an old finding was excluded, that an uncertain requirement was skipped, or that an unreviewed
 * model guess carries a human's authority.
 */

function finding(overrides: Partial<Finding> = {}): Finding {
  return {
    findingId: "f-1",
    comparisonId: "CMP-1",
    standardVersionId: "v-1",
    requirementSetVersion: "REQSET-V3",
    requirementId: "CHG-14-2",
    parentControlId: null,
    requirementTitle: "Emergency change review",
    status: "PARTIAL",
    gapType: null,
    severity: "MEDIUM",
    standardSpanIds: [],
    processSpanIds: [],
    nearestProcessSpanIds: [],
    clauseResults: [],
    reason: "reason",
    recommendedAction: null,
    confidence: 0.9,
    needsHumanReview: false,
    applicabilityStatus: null,
    applicabilityReason: null,
    applicabilityDecidedBy: null,
    ...overrides,
  } as Finding;
}

describe("findingApplicability", () => {
  it("defaults a null status to APPLICABLE", () => {
    // Findings recorded before applicability existed carry null. Defaulting to anything else would
    // retroactively mark historical reviews as excluded or unclear — a comparison is immutable (§19.2),
    // and a display default must not rewrite what it concluded.
    expect(findingApplicability(finding())).toBe("APPLICABLE");
  });

  it("returns the recorded status when present", () => {
    const statuses: ApplicabilityStatus[] = ["APPLICABLE", "NOT_APPLICABLE", "UNCERTAIN"];
    statuses.forEach((status) => {
      expect(findingApplicability(finding({ applicabilityStatus: status }))).toBe(status);
    });
  });
});

describe("needsScopeReview", () => {
  it("is false for a requirement that plainly applies", () => {
    expect(needsScopeReview(finding({ applicabilityStatus: "APPLICABLE" }))).toBe(false);
    // And for the historical null, which means the same thing.
    expect(needsScopeReview(finding())).toBe(false);
  });

  it("is true for UNCERTAIN as well as NOT_APPLICABLE", () => {
    // UNCERTAIN matters most here. Those requirements ARE compared, so nothing in the compliance
    // status hints that applicability was unresolved — if this returned false, the open question
    // would never reach the reviewer at all.
    expect(needsScopeReview(finding({ applicabilityStatus: "UNCERTAIN" }))).toBe(true);
    expect(needsScopeReview(finding({ applicabilityStatus: "NOT_APPLICABLE" }))).toBe(true);
  });
});

describe("isGovernedDecision", () => {
  it("is false when only the rule engine decided", () => {
    expect(isGovernedDecision(finding({ applicabilityDecidedBy: "APPLICABILITY-RULES-V3" })))
      .toBe(false);
  });

  it("is true when a review batch was applied", () => {
    // The distinction the reviewer needs: a human signed this off. On this corpus the extractor's
    // scope-gate predictions were wrong 113 times in 115, so an unreviewed decision and a governed
    // one must not look equally authoritative.
    expect(isGovernedDecision(finding({
      applicabilityDecidedBy: "APPLICABILITY-RULES-V3+CONDROLE-REVIEW-V1",
    }))).toBe(true);
  });

  it("is false when nothing was recorded", () => {
    // Absent provenance is not governance. Treating null as governed would put a "reviewed" badge on
    // every finding predating overrides.
    expect(isGovernedDecision(finding())).toBe(false);
  });
});
