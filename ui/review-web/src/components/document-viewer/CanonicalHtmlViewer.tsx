import { forwardRef, useMemo } from "react";
import { Box, Typography } from "@mui/material";
import type {
  CanonicalDocumentView,
  CanonicalNodeView,
  HydratedEvidence,
} from "@/models/comparison";
import { resolveHighlight } from "@/models/comparison";

/**
 * The reflowable review pane. Architecture §11.2, §19.1.
 *
 * <p>Renders parsed canonical nodes as HTML and marks the cited character range inside the node a
 * span points at. This is the DEFAULT pane, for three reasons the PDF pane cannot match:
 *
 * <ul>
 *   <li>It needs no rendition, so DOCX, CSV, JSON and Markdown display without LibreOffice.</li>
 *   <li>Text reflows, so zoom and narrow viewports work without a coordinate system that drifts.</li>
 *   <li>The text is selectable, searchable and reachable by a screen reader.</li>
 * </ul>
 *
 * <p><strong>Highlighting is arithmetic, not search.</strong> {@link resolveHighlight} subtracts the
 * node's start offset from the span's document-absolute range. When that does not resolve cleanly it
 * returns null and nothing is marked — never a nearest guess. A viewer that token-matches the cited
 * text against paragraphs always finds a best candidate, so a wrong highlight is indistinguishable
 * from a right one; that is the failure this component is built to avoid.
 *
 * <p><strong>Pagination is preserved as markers, not layout.</strong> HTML reflow destroys page
 * boundaries, but a reviewer cites "clause 4.2, page 14". Page numbers therefore travel per node and
 * a rule is drawn wherever the page changes.
 */

export interface CanonicalHtmlViewerProps {
  document: CanonicalDocumentView;
  /** Supporting evidence only. Nearest-passage context must not be highlighted (§18.6). */
  evidence: readonly HydratedEvidence[];
  /** Marks the active finding's nodes with `data-finding-id`, for scroll-to. */
  findingId?: string | null;
  title: string;
  subtitle?: string;
  /** Percentage, matching the PDF pane's control. 100 renders at the base type scale. */
  zoom?: number;
  /** Drives synchronized scrolling; the shell mirrors this pane's position onto the other. */
  onScroll?: () => void;
  /** Which side of the comparison this pane shows; selects the highlight colour. */
  role?: PaneRole;
}

/** Body text width at 100%, in pixels. Mirrors the PDF pane's page metaphor without imposing one. */
const CONTENT_WIDTH = 720;

/**
 * Which side of the comparison a pane is showing.
 *
 * Drives the highlight colour only. The process and the standard are highlighted differently so a
 * reader glancing between panes can tell at once which document they are looking at — the two are
 * otherwise typographically identical, and confusing them is the easiest mistake to make in a
 * side-by-side review.
 */
export type PaneRole = "process" | "standard";

const HIGHLIGHT: Record<PaneRole, { background: string; border: string }> = {
  // Amber for the document under review, green for the reference it is measured against.
  process: { background: "#fff3dc", border: "#d89342" },
  standard: { background: "#e5f0e8", border: "#4d866c" },
};

/**
 * Clearance above a scrolled-to element.
 *
 * Without it a highlight or heading centred by `scrollTo` can sit flush against the top edge of the
 * pane, where the sticky header overlaps it — the element is technically in view and visually cut
 * off. Applied to every node so browser find-in-page lands correctly too.
 */
const SCROLL_MARGIN = 96;

/**
 * Heading level by node type.
 *
 * A heading node renders as a heading so the document keeps a real outline for assistive
 * technology; styling it as bold text would look the same and convey nothing.
 */
function elementFor(nodeType: string): "h2" | "h3" | "p" | "pre" {
  switch (nodeType) {
    case "HEADING":
      return "h2";
    case "SUBHEADING":
      return "h3";
    case "TABLE_ROW":
    case "CODE":
      return "pre";
    default:
      return "p";
  }
}

/**
 * Node types that would render the same table text more than once.
 *
 * <p>The DOCX parser emits a table at three levels of granularity, all covering the same characters:
 * {@code TABLE} for the whole table, {@code TABLE_ROW} for each row with its cells joined by a pipe,
 * and {@code TABLE_CELL} for every individual cell. Rendering all three shows a two-column metadata
 * table as the whole table, then each row, then each cell — the same values three times over, which
 * is what made the top of every DOCX unreadable.
 *
 * <p><strong>Rows are kept</strong> as the level that reads naturally and preserves the pairing
 * between a label and its value. The other two are suppressed for DISPLAY only: the nodes still
 * exist, spans still point at them, and a finding citing a cell still resolves — it just highlights
 * inside the row that contains it, because the row's character range covers the cell's.
 *
 * <p>Suppressing rather than fixing the parser is deliberate. Changing what it emits would shift
 * every span offset in the corpus, and stored findings reference spans that would no longer exist.
 */
const DUPLICATE_TABLE_LEVELS: ReadonlySet<string> = new Set(["TABLE", "TABLE_CELL"]);

/** One node's text, with the cited range wrapped in a mark. */
function NodeText({
  node,
  highlight,
  findingId,
  role,
}: {
  node: CanonicalNodeView;
  highlight: { start: number; end: number } | null;
  findingId?: string | null | undefined;
  role: PaneRole;
}) {
  if (!highlight) {
    return <>{node.text}</>;
  }

  const palette = HIGHLIGHT[role];

  return (
    <>
      {node.text.slice(0, highlight.start)}
      <Box
        component="mark"
        // The anchor the workspace scrolls to. Set only on a node that is actually marked, so
        // scroll-to never targets a pane with nothing highlighted.
        {...(findingId ? { "data-finding-id": findingId } : {})}
        sx={{
          backgroundColor: palette.background,
          // A left rule as well as a wash: the bar survives greyscale printing and the common forms
          // of colour blindness, and it marks where the citation STARTS, which a wash alone does not
          // when the quote wraps across several lines.
          boxShadow: `-8px 0 0 0 ${palette.background}, -11px 0 0 0 ${palette.border}`,
          color: "inherit",
          scrollMarginTop: `${SCROLL_MARGIN}px`,
          borderRadius: "1px",
          // Decoration, not layout: padding here would reflow the sentence and shift every offset
          // the reader is comparing against.
          padding: "1px 0",
        }}
      >
        {node.text.slice(highlight.start, highlight.end)}
      </Box>
      {node.text.slice(highlight.end)}
    </>
  );
}

export const CanonicalHtmlViewer = forwardRef<HTMLDivElement, CanonicalHtmlViewerProps>(
  function CanonicalHtmlViewer(
    {
      document: doc,
      evidence,
      findingId,
      title,
      subtitle,
      zoom = 100,
      onScroll,
      role = "process",
    },
    ref,
  ) {
    /**
     * Nodes actually rendered.
     *
     * A table's redundant levels are dropped, but only when the row level is present to replace
     * them — a parser that emitted just `TABLE` nodes would otherwise lose its tables entirely.
     */
    const visibleNodes = useMemo(() => {
      const hasRows = doc.nodes.some((node) => node.nodeType === "TABLE_ROW");
      if (!hasRows) {
        return doc.nodes;
      }
      return doc.nodes.filter((node) => !DUPLICATE_TABLE_LEVELS.has(node.nodeType));
    }, [doc.nodes]);

    /**
     * Every node, including suppressed ones.
     *
     * Highlight resolution uses the FULL set: a span citing a table cell must still resolve, and it
     * resolves against the cell's own offsets. Whether the cell is rendered separately is a display
     * question; whether the evidence is locatable is not.
     */
    const nodesById = useMemo(() => {
      const map = new Map<string, CanonicalNodeView>();
      doc.nodes.forEach((node) => map.set(node.nodeId, node));
      return map;
    }, [doc.nodes]);

    /**
     * Resolved highlights keyed by node.
     *
     * One entry per node rather than per span: two spans citing adjacent sentences of the same
     * paragraph would otherwise render nested marks. The union of their ranges is what the reviewer
     * should see, and taking min/max is correct because both ranges are within the same node.
     */
    const highlightsByNode = useMemo(() => {
      const rendered = new Set(visibleNodes.map((node) => node.nodeId));
      const map = new Map<string, { start: number; end: number }>();

      for (const item of evidence) {
        let resolved = resolveHighlight(item, nodesById);

        // A span citing a suppressed node resolves to something not on screen, so nothing would be
        // marked. Two shapes need recovering, and only the first was handled:
        //
        //   CELL  — narrower than a row. The row that CONTAINS it covers the same characters, so the
        //           span's offsets land on the same words inside it.
        //   TABLE — WIDER than a row. Nothing contains it, so the containment search below found no
        //           node and the highlight was dropped. A requirement extracted from a whole table
        //           therefore highlighted nothing at all, while its finding cited a span that existed,
        //           carried geometry, and resolved perfectly on the server. Observed on ITSM-CHG-001,
        //           whose four requirements all cite one TABLE node.
        //
        // For a TABLE the rows it spans are the visible replacement, so the highlight goes on every
        // row overlapping its range rather than on one node.
        // Keyed off the span's OWN parent, not off `resolved`. resolveHighlight returns null whenever
        // the range does not select exactly the span's text, which a suppressed TABLE frequently fails:
        // the row level joins cells with a pipe, so the table's text and the span's differ by
        // separators even though both cover the same characters. Guarding on `resolved` therefore
        // skipped recovery in precisely the case that needed it.
        const parent = item.parentNodeId ? nodesById.get(item.parentNodeId) : undefined;
        const parentSuppressed = Boolean(parent && !rendered.has(parent.nodeId));

        if (parentSuppressed) {
          const suppressed = parent;
          const spanStart = item.charStart;
          const spanEnd = item.charEnd;

          const overlapping =
            spanStart == null || spanEnd == null
              ? []
              : visibleNodes.filter(
                  (node) =>
                    node.charStart >= 0
                    && node.charStart < spanEnd
                    && node.charStart + node.text.length > spanStart,
                );

          if (suppressed && suppressed.nodeType === "TABLE" && overlapping.length > 0) {
            // Every row the table's span covers, clipped to that row's own text. Recorded directly
            // rather than through resolveHighlight, which resolves ONE node by id and cannot express
            // a range spanning several.
            for (const row of overlapping) {
              const start = Math.max(0, spanStart! - row.charStart);
              const end = Math.min(row.text.length, spanEnd! - row.charStart);
              if (end <= start) {
                continue;
              }
              const existingRow = map.get(row.nodeId);
              map.set(row.nodeId, {
                start: existingRow ? Math.min(existingRow.start, start) : start,
                end: existingRow ? Math.max(existingRow.end, end) : end,
              });
            }
            continue;
          }

          // A cell: re-measure against the single visible node containing its start.
          const containing = overlapping.find(
            (node) =>
              spanStart != null
              && spanStart >= node.charStart
              && spanStart < node.charStart + node.text.length,
          );
          // Keyed by the span's own parentNodeId, because that is what resolveHighlight looks up —
          // the value is the row we want it measured against.
          resolved =
            containing && item.parentNodeId
              ? resolveHighlight(item, new Map([[item.parentNodeId, containing]]))
              : null;
        }

        if (!resolved) {
          continue;
        }
        const existing = map.get(resolved.nodeId);
        map.set(
          resolved.nodeId,
          existing
            ? {
                start: Math.min(existing.start, resolved.start),
                end: Math.max(existing.end, resolved.end),
              }
            : { start: resolved.start, end: resolved.end },
        );
      }
      return map;
    }, [evidence, nodesById, visibleNodes]);

    const scale = zoom / 100;

    return (
      <Box
        ref={ref}
        onScroll={onScroll}
        sx={{ flex: 1, minHeight: 0, overflow: "auto", bgcolor: "#eceee9", px: 3, py: 3 }}
      >
        <Box
          sx={{
            width: CONTENT_WIDTH,
            maxWidth: "100%",
            mx: "auto",
            // Zoom by type scale rather than transform: the text reflows to the new size instead of
            // being magnified, so line length stays readable and nothing overflows horizontally.
            fontSize: `${scale * 15}px`,
            // A serif body at a fixed measure. This is a document, and reading it as one — rather
            // than as UI text — is what makes a side-by-side comparison legible for long stretches.
            fontFamily: 'Georgia, "Times New Roman", serif',
            lineHeight: 1.68,
            color: "#29312d",
            // Body text must never force the paper wider than the pane either. Document IDs, URLs
            // and long hyphenless strings are common in this corpus, and one of them turns the
            // whole pane into a horizontal scroller.
            overflowWrap: "anywhere",
            bgcolor: "#fff",
            boxShadow: "0 3px 14px rgba(34, 48, 41, .12)",
            borderRadius: 0.5,
            px: 6,
            pt: 5,
            pb: 6,
          }}
        >
          {/* Masthead: what this pane is, in the document's own register rather than as a UI label. */}
          <Box
            sx={{
              display: "flex",
              justifyContent: "space-between",
              fontFamily: (theme) => theme.typography.fontFamily,
              fontSize: 9,
              letterSpacing: "1px",
              color: "#7b837e",
              textTransform: "uppercase",
            }}
          >
            <span>{subtitle}</span>
            <span>{doc.pages > 0 ? `${doc.pages} page${doc.pages === 1 ? "" : "s"}` : ""}</span>
          </Box>

          <Box
            component="h1"
            sx={{
              fontFamily: (theme) => theme.typography.fontFamily,
              fontSize: 29,
              lineHeight: 1.2,
              letterSpacing: "-1px",
              color: "#1e2c26",
              m: "30px 0 20px",
              scrollMarginTop: `${SCROLL_MARGIN}px`,
              // A filename like OPS-CHG-014_Production_Change_Management_Process_v1.3.docx is one
              // unbreakable token. Without an explicit break it forces the paper wider than the
              // pane, and the reader has to scroll sideways to read the rest of the title — and
              // every line of the document with it.
              overflowWrap: "anywhere",
              wordBreak: "break-word",
            }}
          >
            {title}
          </Box>
          {/* The accent rule that separates masthead from body. */}
          <Box sx={{ width: 42, height: 3, bgcolor: "#c88a48", mb: 4 }} />

          {visibleNodes.map((node, index) => {
            const highlight = highlightsByNode.get(node.nodeId) ?? null;
            const previousPage = index > 0 ? visibleNodes[index - 1]?.page : null;
            const startsNewPage =
              node.page != null && previousPage != null && node.page !== previousPage;
            const element = elementFor(node.nodeType);
            const isHeading = element === "h2" || element === "h3";

            return (
              <Box key={node.nodeId}>
                {/* Pagination survives as a marker. Without it a citation reading "page 14" has
                    nothing to correspond to in a reflowed document. */}
                {startsNewPage && (
                  <Box
                    aria-hidden
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: 1,
                      my: 2,
                      color: "text.disabled",
                      "&::before, &::after": {
                        content: '""',
                        flex: 1,
                        borderTop: 1,
                        borderColor: "divider",
                      },
                    }}
                  >
                    <Typography variant="caption">page {node.page}</Typography>
                  </Box>
                )}

                <Box
                  component={element}
                  data-node-id={node.nodeId}
                  sx={{
                    // scrollMarginTop is the fix for a heading landing flush against the pane's top
                    // edge, where it reads as cut off. Applies to headings and body alike so
                    // find-in-page and scroll-to both leave breathing room.
                    scrollMarginTop: `${SCROLL_MARGIN}px`,
                    ...(isHeading
                      ? {
                          fontFamily: (theme) => theme.typography.fontFamily,
                          fontSize: element === "h2" ? "16px" : "14px",
                          fontWeight: 700,
                          letterSpacing: "-.1px",
                          color: "#263d34",
                          m: "26px 0 8px",
                        }
                      : element === "pre"
                        ? {
                            // Tables and code keep their whitespace; prose does not, or every
                            // parsed line break becomes a hard one.
                            whiteSpace: "pre-wrap",
                            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                            fontSize: "0.85em",
                            bgcolor: "#f7f8f7",
                            border: "1px solid #e4e8e5",
                            borderRadius: 0.5,
                            p: 1.5,
                            m: "0 0 14px",
                            overflowX: "auto",
                          }
                        : { m: "0 0 14px" }),
                  }}
                >
                  <NodeText
                    node={node}
                    highlight={highlight}
                    findingId={findingId}
                    role={role}
                  />
                </Box>
              </Box>
            );
          })}

          {visibleNodes.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              This document produced no readable text.
            </Typography>
          )}
        </Box>
      </Box>
    );
  },
);
