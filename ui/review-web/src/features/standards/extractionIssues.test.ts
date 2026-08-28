import { explainIssue, summariseIssues } from "@/features/standards/extractionIssues";

/**
 * Extraction-issue guidance. Architecture §12.8.
 *
 * The point of this translation is that a standards author can fix their document without reading
 * validator internals. These tests pin the two properties that make it useful: the requirement ID is
 * extracted so the author knows WHERE, and an unrecognised message is passed through rather than
 * replaced with something vague.
 */
describe("explainIssue", () => {
  it("extracts the requirement id from the validator prefix", () => {
    const result = explainIssue("5.1: duplicate requirementId within this requirement-set version");

    expect(result.requirementId).toBe("5.1");
    // The author needs to know which clause number is repeated, not the internal rule name.
    expect(result.guidance).toContain("same clause number");
    expect(result.actionable).toBe(true);
  });

  it("marks model faults as not actionable by the author", () => {
    const result = explainIssue("3.2: span SPAN-9 was not supplied as evidence (invented or stale)");

    // Nothing to fix in the document — saying otherwise sends the author hunting for a problem that
    // is not theirs.
    expect(result.actionable).toBe(false);
    expect(result.guidance).toContain("Nothing needs fixing in your");
  });

  it("passes an unrecognised message through verbatim", () => {
    const raw = "7.4: some entirely new validator rule fired";
    const result = explainIssue(raw);

    // A literal message is searchable and reportable; a friendly substitute would hide the cause.
    expect(result.guidance).toBe("some entirely new validator rule fired");
    expect(result.requirementId).toBe("7.4");
  });

  it("handles a message with no requirement prefix", () => {
    const result = explainIssue("extraction exhausted retries");

    expect(result.requirementId).toBeNull();
    expect(result.guidance).toBe("extraction exhausted retries");
  });
});

describe("summariseIssues", () => {
  it("groups the same problem across requirements into one item", () => {
    const result = summariseIssues([
      "5.1: duplicate requirementId within this requirement-set version",
      "5.2: duplicate requirementId within this requirement-set version",
      "6.1: duplicate requirementId within this requirement-set version",
    ]);

    // One thing to fix, listed once with the affected clauses — not three near-identical lines,
    // which is how an author concludes the tool is broken rather than their document.
    expect(result).toHaveLength(1);
    expect(result[0]?.requirementIds).toEqual(["5.1", "5.2", "6.1"]);
  });

  it("puts author-actionable issues first", () => {
    const result = summariseIssues([
      "3.2: span SPAN-9 was not supplied as evidence (invented or stale)",
      "5.1: duplicate requirementId within this requirement-set version",
    ]);

    expect(result[0]?.actionable).toBe(true);
    expect(result[1]?.actionable).toBe(false);
  });

  it("returns nothing for a clean version", () => {
    expect(summariseIssues([])).toEqual([]);
  });
});
