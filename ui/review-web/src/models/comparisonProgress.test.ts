import { comparisonProgress } from "@/models/comparison";

/**
 * In-flight progress reporting. Architecture §21.
 *
 * <p>What this replaces: the raw enum name. `SEARCHING_STANDARDS` rendered as "searching standards",
 * which reads as though the tool is hunting for the standard rather than working through a review,
 * and carried no sense of how far along the run was.
 */

describe("comparisonProgress", () => {
  it("gives every stage a reader-facing label, never the enum name", () => {
    const states = [
      "UPLOADED",
      "PARSING_DOCUMENT",
      "RENDERING_DOCUMENT",
      "GENERATING_SPANS",
      "GENERATING_CHUNKS",
      "GENERATING_EMBEDDINGS",
      "SEARCHING_STANDARDS",
      "RETRIEVING_REQUIREMENT_EVIDENCE",
      "EVALUATING_REQUIREMENT",
      "AGGREGATING_CONTROLS",
      "GENERATING_FINDINGS",
    ];

    states.forEach((status) => {
      const { label } = comparisonProgress({ status });
      expect(label).not.toContain("_");
      expect(label).not.toBe(status.toLowerCase());
      expect(label.length).toBeGreaterThan(3);
    });
  });

  it("reports no percentage for a stage without a measured count", () => {
    // The stages have no denominator, so any number would be invented. Worse, the worker does not
    // report stage changes mid-run, so an invented number sits unchanged for minutes and invites the
    // reader to estimate a finish time from it. A null tells the caller to go indeterminate.
    const ordered = [
      "UPLOADED",
      "PARSING_DOCUMENT",
      "GENERATING_SPANS",
      "GENERATING_EMBEDDINGS",
      "SEARCHING_STANDARDS",
      "RETRIEVING_REQUIREMENT_EVIDENCE",
      "AGGREGATING_CONTROLS",
      "GENERATING_FINDINGS",
    ];

    ordered.forEach((status) => {
      expect(comparisonProgress({ status }).percent).toBeNull();
    });
  });

  it("prefers the measured count over a stale stage", () => {
    // The regression: the worker advances its stage locally and only reports back on completion, so
    // review-core kept serving SEARCHING_STANDARDS while the run was most of the way through
    // evaluation. The bar sat at 62% throughout.
    const stale = comparisonProgress({
      status: "SEARCHING_STANDARDS",
      evaluatedRequirements: 118,
      totalRequirements: 120,
    });

    // 118/120 is the measured truth regardless of the stage the backend still reports.
    expect(stale.percent).toBe(98);
    expect(stale.label).toBe("Comparing requirements");
  });

  it("uses the measured ratio during evaluation", () => {
    // The only stage with a real denominator, so the only one that yields a percentage. It is the
    // ratio itself, not a slice of a band: a reader shown "50%" alongside "100 of 200 requirements"
    // can check the number against the count, which is the point of showing it.
    const half = comparisonProgress({
      status: "EVALUATING_REQUIREMENT",
      evaluatedRequirements: 100,
      totalRequirements: 200,
    });
    const nearlyDone = comparisonProgress({
      status: "EVALUATING_REQUIREMENT",
      evaluatedRequirements: 199,
      totalRequirements: 200,
    });

    expect(half.percent).toBe(50);
    // Capped at 99 while running: 199/200 rounds to 100, and a bar at 100% on a job still working
    // sends the reader looking for results that are not there.
    expect(nearlyDone.percent).toBe(99);
    expect(nearlyDone.percent!).toBeGreaterThan(half.percent!);
  });

  it("does not divide by zero before the requirement count is known", () => {
    // Real: totalRequirements is null until extraction has published a requirement set.
    const progress = comparisonProgress({
      status: "EVALUATING_REQUIREMENT",
      evaluatedRequirements: 0,
      totalRequirements: null,
    });

    // No denominator means no percentage — and crucially not NaN, which would render as "NaN%".
    expect(progress.percent).toBeNull();
    expect(progress.label).toBe("Comparing requirements");
  });

  it("falls back for an unrecognised state rather than rendering nothing", () => {
    // A state added to the backend enum before this map is updated must still show a bar.
    const progress = comparisonProgress({ status: "SOME_NEW_STAGE" });

    expect(progress.label).toBe("Working");
    expect(progress.percent).toBeNull();
  });
});
