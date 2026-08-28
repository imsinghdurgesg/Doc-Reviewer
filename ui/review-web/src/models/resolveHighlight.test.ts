import {
  resolveHighlight,
  type CanonicalNodeView,
  type HydratedEvidence,
} from "@/models/comparison";

/**
 * The HTML pane marks the cited sentence, or nothing at all.
 *
 * The failure this guards against is specific. A viewer that locates evidence by searching for the
 * model's words always finds a best match, so a wrong one is indistinguishable from a right one —
 * it just highlights the wrong paragraph, confidently. Here the location is arithmetic, and every
 * case where the arithmetic does not hold returns null instead of a plausible guess.
 */

const NODE: CanonicalNodeView = {
  nodeId: "n2",
  nodeType: "PARAGRAPH",
  sectionPath: ["4. Change Management"],
  page: 1,
  paragraph: 1,
  ordinal: 1,
  text: "Changes must be approved before deployment. The approver must be independent.",
  textHash: "sha256:abc",
  // The node begins 14 characters into the document, so span offsets are all shifted by 14.
  charStart: 14,
  charEnd: 91,
};

const nodesById = new Map<string, CanonicalNodeView>([[NODE.nodeId, NODE]]);

function evidence(overrides: Partial<HydratedEvidence> = {}): HydratedEvidence {
  return {
    spanId: "SPAN-1",
    page: 1,
    sectionPath: ["4. Change Management"],
    text: "The approver must be independent.",
    boundingBoxes: [],
    parentNodeId: "n2",
    // Document-absolute: 14 (node start) + 44 (offset within node).
    charStart: 58,
    charEnd: 91,
    textHash: "sha256:span",
    ...overrides,
  };
}

describe("resolveHighlight", () => {
  it("converts a document-absolute range into a node-relative one", () => {
    const resolved = resolveHighlight(evidence(), nodesById);

    expect(resolved).not.toBeNull();
    expect(resolved!.nodeId).toBe("n2");
    expect(NODE.text.slice(resolved!.start, resolved!.end)).toBe(
      "The approver must be independent.",
    );
  });

  it("marks the cited sentence, not the whole paragraph", () => {
    const resolved = resolveHighlight(evidence(), nodesById)!;

    // The node holds two sentences. Highlighting both would cite the approval rule for a finding
    // about approver independence — over-citation that makes the highlight useless as proof.
    expect(resolved.start).toBeGreaterThan(0);
    expect(NODE.text.slice(resolved.start, resolved.end)).not.toContain(
      "Changes must be approved",
    );
  });

  it("refuses when the span names no node", () => {
    expect(resolveHighlight(evidence({ parentNodeId: null }), nodesById)).toBeNull();
  });

  it("refuses when the node was filtered out of the view", () => {
    // Repeated headers and footers are dropped by default; nothing citable points at them, but a
    // stale finding might.
    expect(resolveHighlight(evidence({ parentNodeId: "header-1" }), nodesById)).toBeNull();
  });

  it("refuses when the node carries no offset", () => {
    const noOffset = new Map<string, CanonicalNodeView>([
      ["n2", { ...NODE, charStart: -1, charEnd: -1 }],
    ]);
    expect(resolveHighlight(evidence(), noOffset)).toBeNull();
  });

  it("clamps a range that overruns the node by a character, when the text still matches", () => {
    // Real data does this: SpanBuilder strips span text, so a node with trailing whitespace yields
    // a range one or two characters wider than the text it names. Refusing outright dropped
    // legitimate highlights on table nodes.
    const resolved = resolveHighlight(evidence({ charEnd: 92 }), nodesById);
    expect(resolved).not.toBeNull();
    expect(NODE.text.slice(resolved!.start, resolved!.end)).toBe(
      "The approver must be independent.",
    );
  });

  it("still refuses a wildly out-of-range end, because the text will not match", () => {
    // Clamping to the node end selects far more than the span cites, and the verification catches
    // it — the clamp is a tolerance for off-by-one, not a licence to highlight anything.
    expect(resolveHighlight(evidence({ charStart: 14, charEnd: 9999 }), nodesById)).toBeNull();
  });

  it("refuses an inverted or empty range", () => {
    expect(resolveHighlight(evidence({ charStart: 91, charEnd: 58 }), nodesById)).toBeNull();
  });

  it("refuses when the resolved text is not what the span cites", () => {
    // The offsets are in bounds but point at the wrong sentence — what a re-parse that inserted or
    // removed text upstream produces. This is the case that would otherwise be drawn confidently.
    const shifted = resolveHighlight(evidence({ charStart: 14, charEnd: 47 }), nodesById);
    expect(shifted).toBeNull();
  });
});
