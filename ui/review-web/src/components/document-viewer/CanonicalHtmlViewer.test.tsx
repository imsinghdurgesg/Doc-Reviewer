import { render, screen } from "@testing-library/react";
import { CanonicalHtmlViewer } from "@/components/document-viewer/CanonicalHtmlViewer";
import type {
  CanonicalDocumentView,
  CanonicalNodeView,
  HydratedEvidence,
} from "@/models/comparison";

/**
 * The text pane marks the cited sentence, and nothing else.
 *
 * These assert on rendered output rather than on {@code resolveHighlight}, which is unit-tested
 * separately. What can break here and not there: a node rendered without its offset, two spans in
 * one node producing nested marks, or a `data-finding-id` landing on a node that was never
 * highlighted — which would make the workspace scroll to a pane showing no evidence.
 */

const NODES: CanonicalNodeView[] = [
  {
    nodeId: "n1",
    nodeType: "HEADING",
    sectionPath: ["4. Change Management"],
    page: 1,
    paragraph: 0,
    ordinal: 0,
    text: "4.1 Approval",
    textHash: "sha256:n1",
    charStart: 0,
    charEnd: 12,
  },
  {
    nodeId: "n2",
    nodeType: "PARAGRAPH",
    sectionPath: ["4. Change Management"],
    page: 1,
    paragraph: 1,
    ordinal: 1,
    text: "Changes must be approved before deployment. The approver must be independent.",
    textHash: "sha256:n2",
    charStart: 14,
    charEnd: 91,
  },
  {
    nodeId: "n3",
    nodeType: "PARAGRAPH",
    sectionPath: ["5. Records"],
    // A later page, so the viewer must draw a page-break marker before it.
    page: 2,
    paragraph: 2,
    ordinal: 2,
    text: "Approval evidence is retained for seven years.",
    textHash: "sha256:n3",
    charStart: 93,
    charEnd: 138,
  },
];

const DOC: CanonicalDocumentView = {
  documentVersionId: "DOC-V1",
  title: "Change Release Process",
  contentHash: "sha256:doc",
  pages: 2,
  nodes: NODES,
};

function evidence(overrides: Partial<HydratedEvidence> = {}): HydratedEvidence {
  return {
    spanId: "SPAN-1",
    page: 1,
    sectionPath: [],
    text: "The approver must be independent.",
    boundingBoxes: [],
    parentNodeId: "n2",
    charStart: 58,
    charEnd: 91,
    textHash: "sha256:span",
    ...overrides,
  };
}

function renderViewer(items: HydratedEvidence[], findingId: string | null = "F-1") {
  return render(
    <CanonicalHtmlViewer
      document={DOC}
      evidence={items}
      findingId={findingId}
      title="change-release-process.txt"
      subtitle="Current process"
    />,
  );
}

describe("CanonicalHtmlViewer", () => {
  it("renders every node's text", () => {
    renderViewer([]);
    expect(screen.getByText(/4.1 Approval/)).toBeInTheDocument();
    expect(screen.getByText(/Approval evidence is retained/)).toBeInTheDocument();
  });

  it("marks only the cited sentence", () => {
    const { container } = renderViewer([evidence()]);

    const marks = container.querySelectorAll("mark");
    expect(marks).toHaveLength(1);
    expect(marks[0]?.textContent).toBe("The approver must be independent.");
    // The sibling sentence stays unmarked; highlighting the whole paragraph would cite the approval
    // rule for a finding about approver independence.
    expect(marks[0]?.textContent).not.toContain("Changes must be approved");
  });

  it("anchors the finding id on the mark it actually drew", () => {
    const { container } = renderViewer([evidence()]);

    const anchor = container.querySelector('[data-finding-id="F-1"]');
    expect(anchor).not.toBeNull();
    expect(anchor?.tagName.toLowerCase()).toBe("mark");
  });

  it("draws no mark and no anchor when the span cannot be resolved", () => {
    // Names a node the view does not contain — a repeated header, or a stale finding.
    const { container } = renderViewer([evidence({ parentNodeId: "missing" })]);

    expect(container.querySelectorAll("mark")).toHaveLength(0);
    // Critically, no anchor either: the workspace must not scroll a pane to evidence it never drew.
    expect(container.querySelector('[data-finding-id="F-1"]')).toBeNull();
  });

  it("draws no mark when the resolved text is not what the span cites", () => {
    // In-bounds offsets pointing at the wrong sentence, as a shifted re-parse produces.
    const { container } = renderViewer([evidence({ charStart: 14, charEnd: 47 })]);
    expect(container.querySelectorAll("mark")).toHaveLength(0);
  });

  it("merges two spans in one node into a single mark", () => {
    const { container } = renderViewer([
      evidence({ spanId: "S1", text: "Changes must be approved before deployment.", charStart: 14, charEnd: 57 }),
      evidence({ spanId: "S2", text: "The approver must be independent.", charStart: 58, charEnd: 91 }),
    ]);

    // Nested or adjacent marks would render as separate highlights of the same paragraph.
    const marks = container.querySelectorAll("mark");
    expect(marks).toHaveLength(1);
    expect(marks[0]?.textContent).toContain("Changes must be approved");
    expect(marks[0]?.textContent).toContain("independent");
  });

  it("marks a page change so pagination survives reflow", () => {
    renderViewer([]);
    // n3 is on page 2 while n2 is on page 1; without this a citation reading "page 2" has nothing
    // to correspond to in a reflowed document.
    expect(screen.getByText("page 2")).toBeInTheDocument();
  });

  it("renders headings as headings", () => {
    renderViewer([]);
    // Not cosmetic: the document keeps a real outline for assistive technology.
    expect(screen.getByRole("heading", { name: /4.1 Approval/ })).toBeInTheDocument();
  });

  it("gives every node clearance so a scrolled-to heading is not clipped", () => {
    const { container } = renderViewer([]);

    // Without scroll-margin-top a heading centred by scrollTo can land flush against the pane's top
    // edge, technically in view and visually cut off — which is how it was reported.
    const nodes = container.querySelectorAll("[data-node-id]");
    expect(nodes.length).toBeGreaterThan(0);
    nodes.forEach((node) => {
      // sx compiles to a class, so the value is only visible via getComputedStyle.
      const clearance = getComputedStyle(node as HTMLElement).scrollMarginTop;
      expect(clearance).not.toBe("");
      expect(parseInt(clearance, 10)).toBeGreaterThan(0);
    });
  });

  it("colours the highlight by pane role", () => {
    const { container: process } = render(
      <CanonicalHtmlViewer document={DOC} evidence={[evidence()]} title="p" role="process" />,
    );
    const { container: standard } = render(
      <CanonicalHtmlViewer document={DOC} evidence={[evidence()]} title="s" role="standard" />,
    );

    // Distinct colours so a reader glancing between panes can tell which document they are in.
    const processMark = process.querySelector("mark") as HTMLElement;
    const standardMark = standard.querySelector("mark") as HTMLElement;
    const processBg = getComputedStyle(processMark).backgroundColor;
    const standardBg = getComputedStyle(standardMark).backgroundColor;

    expect(processBg).toBeTruthy();
    expect(standardBg).toBeTruthy();
    expect(processBg).not.toBe(standardBg);
  });

  describe("table nodes", () => {
    // The DOCX parser emits the same table text at three levels of granularity. Rendering all three
    // showed every metadata value three times and made the top of every DOCX unreadable.
    const TABLE_DOC: CanonicalDocumentView = {
      documentVersionId: "DOC-V2",
      title: "Process",
      contentHash: "sha256:doc",
      pages: 1,
      nodes: [
        {
          nodeId: "t1",
          nodeType: "TABLE",
          sectionPath: [],
          page: 1,
          paragraph: 0,
          ordinal: 0,
          text: "Process ID | OPS-CHG-014",
          textHash: "sha256:t1",
          charStart: 0,
          charEnd: 24,
        },
        {
          nodeId: "c1",
          nodeType: "TABLE_CELL",
          sectionPath: [],
          page: 1,
          paragraph: 0,
          ordinal: 1,
          text: "Process ID",
          textHash: "sha256:c1",
          charStart: 0,
          charEnd: 10,
        },
        {
          nodeId: "c2",
          nodeType: "TABLE_CELL",
          sectionPath: [],
          page: 1,
          paragraph: 0,
          ordinal: 2,
          text: "OPS-CHG-014",
          textHash: "sha256:c2",
          charStart: 13,
          charEnd: 24,
        },
        {
          nodeId: "r1",
          nodeType: "TABLE_ROW",
          sectionPath: [],
          page: 1,
          paragraph: 0,
          ordinal: 3,
          text: "Process ID | OPS-CHG-014",
          textHash: "sha256:r1",
          charStart: 0,
          charEnd: 24,
        },
      ],
    };

    it("renders the row once, not the table and every cell as well", () => {
      const { container } = render(
        <CanonicalHtmlViewer document={TABLE_DOC} evidence={[]} title="p" />,
      );

      const rendered = container.querySelectorAll("[data-node-id]");
      expect(rendered).toHaveLength(1);
      expect(rendered[0]?.getAttribute("data-node-id")).toBe("r1");
    });

    it("still highlights a span that cites a suppressed cell", () => {
      // The finding points at the cell; the cell is not rendered. The highlight must appear in the
      // row that contains it rather than vanishing.
      const cellEvidence: HydratedEvidence = {
        spanId: "SPAN-CELL",
        page: 1,
        sectionPath: [],
        text: "OPS-CHG-014",
        boundingBoxes: [],
        parentNodeId: "c2",
        charStart: 13,
        charEnd: 24,
        textHash: "sha256:span",
      };

      const { container } = render(
        <CanonicalHtmlViewer document={TABLE_DOC} evidence={[cellEvidence]} title="p" />,
      );

      const mark = container.querySelector("mark");
      expect(mark).not.toBeNull();
      expect(mark?.textContent).toBe("OPS-CHG-014");
    });

    it("keeps table nodes when the parser emitted no rows", () => {
      // Defensive: dropping TABLE without a row level to replace it would lose the table entirely.
      const noRows = {
        ...TABLE_DOC,
        nodes: TABLE_DOC.nodes.filter((n) => n.nodeType !== "TABLE_ROW"),
      };
      const { container } = render(
        <CanonicalHtmlViewer document={noRows} evidence={[]} title="p" />,
      );
      expect(container.querySelectorAll("[data-node-id]").length).toBeGreaterThan(0);
    });
  });

  it("reports an empty document rather than rendering blank", () => {
    render(
      <CanonicalHtmlViewer
        document={{ ...DOC, nodes: [] }}
        evidence={[]}
        title="empty.txt"
      />,
    );
    expect(screen.getByText(/produced no readable text/)).toBeInTheDocument();
  });
});
