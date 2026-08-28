import { suggestVersion } from "./StandardsAdminPage";
import type { StandardVersionSummary } from "@/models/comparison";

/**
 * Next-version suggestion. Architecture §19.2.
 *
 * A version label cannot be reused, so the suggested value must be one the backend will accept. The
 * previous implementation used `parseFloat` + `Math.floor`, which both DROPPED segments past the
 * second dot and treated "2" and "2.0" as equal — so it could suggest a label the backend rejects
 * as a duplicate, or skip a minor version the organisation was deliberately using.
 */
describe("suggestVersion", () => {
  const version = (v: string): StandardVersionSummary =>
    ({ version: v } as StandardVersionSummary);

  it("suggests 1 when there are no versions", () => {
    expect(suggestVersion([])).toBe("1");
  });

  it("increments the minor segment rather than jumping a major version", () => {
    // The old behaviour returned "3" here, silently discarding the 2.x series.
    expect(suggestVersion([version("2.4")])).toBe("2.5");
  });

  it("preserves the numbering depth the standard uses", () => {
    expect(suggestVersion([version("2.4.1")])).toBe("2.4.2");
    expect(suggestVersion([version("2")])).toBe("3");
  });

  it("does not suggest a label equivalent to an existing one", () => {
    // "2.0" exists, so suggesting "2" would collide — the backend now treats them as one version.
    expect(suggestVersion([version("2.0")])).not.toBe("2");
    expect(suggestVersion([version("2.0")])).toBe("2.1");
  });

  it("compares segments numerically, not as text", () => {
    // "2.10" is above "2.9"; a string or float comparison gets this wrong.
    expect(suggestVersion([version("2.9"), version("2.10")])).toBe("2.11");
  });

  it("picks the highest of several versions", () => {
    expect(suggestVersion([version("1.0"), version("2.4"), version("1.9")])).toBe("2.5");
  });

  it("ignores a leading v", () => {
    expect(suggestVersion([version("v2.4")])).toBe("2.5");
  });

  it("falls back to 1 when no version is numeric", () => {
    // A standard labelled by quarter has no numeric series to continue; the field stays editable.
    expect(suggestVersion([version("2024-Q1")])).toBe("1");
  });

  it("ignores non-numeric labels alongside numeric ones", () => {
    expect(suggestVersion([version("draft-a"), version("2.4")])).toBe("2.5");
  });

  it("handles a deeper series than parseFloat could represent", () => {
    // parseFloat("1.2.3.4") is 1.2 — everything after the second dot was lost.
    expect(suggestVersion([version("1.2.3.4")])).toBe("1.2.3.5");
  });
});
