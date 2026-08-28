import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  CircularProgress,
  LinearProgress,
  Stack,
  Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { overlineLabel, palette } from "@/app/theme";
import {
  TERMINAL_COMPARISON_STATES,
  type CanonicalDocumentView,
  type ComparisonDetail,
  type Finding,
  type HydratedEvidence,
  parseUnevaluated,
  comparisonProgress,
} from "@/models/comparison";
import { ComparisonSummaryBar } from "@/features/comparisons/ComparisonSummaryBar";
import { FindingsPanel } from "@/features/findings/FindingsPanel";
import { CanonicalHtmlViewer } from "@/components/document-viewer/CanonicalHtmlViewer";
import { ViewerControlBar, type MobilePane } from "@/features/comparisons/ViewerControlBar";
import { useScrollToFinding, useViewerShell } from "@/features/comparisons/useViewerShell";

/**
 * A pane with nothing to show yet, or nothing to show at all.
 *
 * Distinguishes the two: a spinner says "still coming", a message says "this will not arrive". A
 * single empty pane cannot be told apart from a broken one, which is the reading a reviewer defaults
 * to when a document does not appear.
 */
function PaneStatus({ loading, message }: { loading: boolean; message: string }) {
  if (loading) {
    return (
      <Stack sx={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
        <CircularProgress size={24} />
      </Stack>
    );
  }
  return (
    <Box sx={{ p: 2 }}>
      <Alert severity="info">{message}</Alert>
    </Box>
  );
}

/**
 * Side-by-side review workspace. Architecture §19.1.
 *
 * Three panes: findings, the uploaded process, the standard. Selecting a finding navigates BOTH
 * viewers to their side of it — the reviewer reads one claim and its proof on each side, rather than
 * reading two documents and holding the comparison in their head.
 *
 * Evidence is fetched per finding rather than shipped with the comparison. A comparison over 200
 * requirements carries thousands of spans with bounding boxes; sending them all would make the
 * initial load slow to serve a view that shows one finding at a time.
 */
export function ComparisonWorkspace() {
  const { comparisonId } = useParams<{ comparisonId: string }>();
  const [selectedFinding, setSelectedFinding] = useState<Finding | null>(null);
  const [activeReferenceIndex, setActiveReferenceIndex] = useState(0);

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [mobilePane, setMobilePane] = useState<MobilePane>("process");
  const shell = useViewerShell();

  const comparison = useQuery({
    queryKey: ["comparison", comparisonId],
    queryFn: () => apiClient.get<ComparisonDetail>(`/comparisons/${comparisonId}`),
    enabled: Boolean(comparisonId),
    // Evaluation is asynchronous (§21) and makes one model call per requirement, so a comparison
    // opened immediately after creation has no findings yet. Polling stops at a terminal state —
    // §19.2 fixes the result at that point, so there is nothing further to learn by asking again.
    refetchInterval: (query) =>
      query.state.data && TERMINAL_COMPARISON_STATES.includes(query.state.data.status)
        ? false
        : 2500,
  });

  /**
   * Runs the same review again.
   *
   * Navigates to the NEW comparison rather than refreshing this one: the original is immutable
   * (§19.2) and keeps its own findings and failure reason, so staying here would show the old result
   * while the new run proceeded invisibly elsewhere.
   */
  const rerun = useMutation({
    mutationFn: () =>
      apiClient.post<ComparisonDetail>(`/comparisons/${comparisonId}/rerun`),
    onSuccess: (created) => {
      // History gains a row; invalidate so it appears without a manual refresh.
      void queryClient.invalidateQueries({ queryKey: ["comparisons"] });
      navigate(`/comparisons/${created.publicId}`);
    },
  });

  const evidence = useQuery({
    queryKey: ["evidence", comparisonId, selectedFinding?.findingId],
    queryFn: () =>
      apiClient.get<{
        standardEvidence: HydratedEvidence[];
        processEvidence: HydratedEvidence[];
        nearestProcessEvidence: HydratedEvidence[];
      }>(`/comparisons/${comparisonId}/findings/${selectedFinding?.findingId}/evidence`),
    // Only when a finding is selected: there is no evidence to fetch for "nothing selected".
    enabled: Boolean(comparisonId && selectedFinding),
  });

  const activeReference = comparison.data?.references[activeReferenceIndex];

  /**
   * Parsed nodes for the text pane.
   *
   * Fetched only while that pane is showing, and separately per document so one failing does not
   * blank the other. A comparison's canonical form does not change once processing is done (§19.2),
   * so these are cached indefinitely rather than refetched alongside the polling comparison query.
   */
  const processCanonical = useQuery({
    queryKey: ["canonical", "upload", comparison.data?.document.documentVersionId],
    queryFn: () =>
      apiClient.get<CanonicalDocumentView>(
        `/uploads/${comparison.data?.document.documentVersionId}/canonical`,
      ),
    enabled: Boolean(comparison.data?.document.documentVersionId),
    staleTime: Infinity,
  });

  const standardCanonical = useQuery({
    queryKey: ["canonical", "standard", activeReference?.standardVersionId],
    queryFn: () =>
      apiClient.get<CanonicalDocumentView>(
        `/standards/${activeReference?.standardVersionId}/canonical`,
      ),
    enabled: Boolean(activeReference?.standardVersionId),
    staleTime: Infinity,
  });

  // Boxes are flattened across the finding's spans; the viewer filters to the page it is showing.
  //
  // ONLY supporting evidence is highlighted. `nearestProcessEvidence` is deliberately excluded:
  // §18.6 stores those as context ("this is what was considered and rejected"), not as proof, and
  // highlighting them under a MISSING finding reads as "here is where the requirement is met" for a
  // requirement that is absent. They are surfaced as a caption below instead.
  const nearestPassages = evidence.data?.nearestProcessEvidence ?? [];
  const hasSupportingProcessEvidence = (evidence.data?.processEvidence ?? []).length > 0;

  // Requirements the run could not evaluate, split into id and reason for display.
  const unevaluated = useMemo(
    () => (comparison.data?.unevaluatedRequirements ?? []).map(parseUnevaluated),
    [comparison.data],
  );

  // The panes are scroll containers, so the selected finding is reached by scrolling to its mark.
  // Depends on the evidence and canonical queries: the mark does not exist until the data that
  // resolves it has arrived.
  useScrollToFinding(
    selectedFinding?.findingId,
    [shell.leftRef, shell.rightRef],
    shell.suppressSync,
    [evidence.data, processCanonical.data, standardCanonical.data],
  );

  if (comparison.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", justifyContent: "center", height: "100%" }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (comparison.isError || !comparison.data) {
    return (
      <Box sx={{ p: 3 }}>
        <Alert severity="error">
          {comparison.error instanceof Error
            ? comparison.error.message
            : "Could not load this comparison."}
        </Alert>
      </Box>
    );
  }

  const detail = comparison.data;

  return (
    <Stack sx={{ height: "100%" }}>
      <Stack
        direction="row"
        spacing={2}
       
        sx={{ alignItems: "baseline", px: 2, py: 1, borderBottom: 1, borderColor: "divider", flexWrap: "wrap" }}
      >
        <Typography variant="subtitle1">{detail.document.filename}</Typography>
        {activeReference && (
          <Typography variant="body2" color="text.secondary">
            vs {activeReference.standardTitle} v{activeReference.standardVersion}
          </Typography>
        )}
        {/* Stated as relevance, never as a compliance score (§15.1). Labelling it "score" alone
            invites reading 0.91 as "91% compliant", which it is not.
            Hidden when zero: an explicitly selected standard was never ranked, and rendering
            "relevance 0.00" would read as "irrelevant" rather than "not applicable". */}
        {activeReference && activeReference.retrievalScore > 0 && (
          <Typography variant="caption" color="text.secondary">
            retrieval relevance {activeReference.retrievalScore.toFixed(2)}
          </Typography>
        )}
        {/* The reproducibility record (§19.2), visible rather than buried in an export. Rendered
            only once the worker has reported it: the fields are null until then, and printing
            "null · prompt null" would look like data loss rather than work in progress. */}
        {detail.comparisonModelAlias && (
          <Typography variant="caption" color="text.secondary">
            {detail.comparisonModelAlias} · prompt {detail.promptVersion} ·{" "}
            {detail.requirementSetVersion} · {detail.retrievalStrategyVersion}
          </Typography>
        )}
      </Stack>

      {!TERMINAL_COMPARISON_STATES.includes(detail.status) && (() => {
        // A plain-language stage, not the raw enum name. Evaluation makes one model call per
        // requirement and can take minutes (§21), so the reader needs to see that work is happening
        // — a bare spinner cannot distinguish slow from stuck.
        //
        // The bar is determinate ONLY once evaluation reports counts. Before that there is no
        // measured fraction, and the invented per-stage percentages it used to show were worse than
        // nothing: they let a reader estimate a finish time from a number that never moved.
        const { label, percent } = comparisonProgress(detail);
        return (
          <Box
            sx={{ px: 3, py: 2, borderBottom: 1, borderColor: "divider", bgcolor: palette.chrome }}
          >
            <Stack direction="row" spacing={2} sx={{ alignItems: "center", mb: 1 }}>
              <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{label}</Typography>
              {/* The measured count, where one exists. It is the only honest number here. */}
              {detail.totalRequirements ? (
                <Typography sx={{ fontSize: 13, color: palette.muted }}>
                  {detail.evaluatedRequirements ?? 0} of {detail.totalRequirements} requirements
                </Typography>
              ) : null}
              <Box sx={{ flex: 1 }} />
              {percent === null ? null : (
                <Typography sx={{ ...overlineLabel, color: palette.forest }}>{percent}%</Typography>
              )}
            </Stack>
            {percent === null ? (
              <LinearProgress sx={{ height: 6, borderRadius: 3 }} />
            ) : (
              <LinearProgress
                variant="determinate"
                value={percent}
                sx={{ height: 6, borderRadius: 3 }}
              />
            )}
          </Box>
        );
      })()}

      {detail.status === "PARTIALLY_COMPLETED" && detail.stateMessage && (
        <Box sx={{ px: 2, py: 1 }}>
          {/* §18.4: the findings that succeeded are valid and shown; the gap must still be stated,
              or a partial review reads as a complete one.

              The affected controls are NAMED, not just counted. A reviewer told only "1 of 15 could
              not be evaluated" cannot judge whether the gap matters — the missing control might be
              the one the review was for. Naming it makes the limitation bounded and checkable. */}
          <Alert severity="warning">
            <AlertTitle sx={{ mb: unevaluated.length > 0 ? 0.5 : 0 }}>
              {detail.stateMessage}
            </AlertTitle>
            {unevaluated.length > 0 && (
              <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                {unevaluated.map((item) => (
                  <Box component="li" key={item.requirementId}>
                    <Typography variant="body2" component="span" sx={{ fontWeight: 600 }}>
                      {item.requirementId}
                    </Typography>
                    {item.reason && (
                      <Typography variant="body2" component="span" color="text.secondary">
                        {" — "}{item.reason}
                      </Typography>
                    )}
                  </Box>
                ))}
              </Box>
            )}
          </Alert>
        </Box>
      )}

      {detail.status === "FAILED" && (
        <Box sx={{ px: 2, py: 1 }}>
          {/* The message alone was a dead end: it says to rerun, and there was no way to. Reaching the
              upload screen and reconstructing the same standard selection from memory is not a remedy.
              The action runs the same document and standards again as a NEW comparison — this one keeps
              its findings and its failure reason (§19.2). */}
          <Alert
            severity="error"
            action={
              <Button
                size="small"
                color="inherit"
                disabled={rerun.isPending}
                onClick={() => rerun.mutate()}
              >
                {rerun.isPending ? "Starting…" : "Rerun"}
              </Button>
            }
          >
            {detail.stateMessage ?? "This comparison could not be completed."}
          </Alert>
        </Box>
      )}

      <ComparisonSummaryBar detail={detail} findings={activeReference?.findings ?? []} />

      <ViewerControlBar
        zoom={shell.zoom}
        onZoomIn={shell.zoomIn}
        onZoomOut={shell.zoomOut}
        onFitWidth={shell.fitWidth}
        scrollTogether={shell.scrollTogether}
        onScrollTogetherChange={shell.setScrollTogether}
        fullscreen={shell.fullscreen}
        onToggleFullscreen={shell.toggleFullscreen}
        mobilePane={mobilePane}
        onMobilePaneChange={setMobilePane}
      />

      <Stack direction="row" sx={{ flex: 1, minHeight: 0 }} ref={shell.documentsAreaRef}>
        <Box
          sx={{
            width: 380,
            flexShrink: 0,
            borderRight: 1,
            borderColor: "divider",
            // Hidden in full screen: the point of the mode is the documents, and a findings list
            // occupying a third of the screen defeats it.
            display: shell.fullscreen ? "none" : "block",
          }}
        >
          <FindingsPanel
            findings={activeReference?.findings ?? []}
            selectedFindingId={selectedFinding?.findingId ?? null}
            onSelectFinding={setSelectedFinding}
          />
        </Box>

        <Box
          sx={{
            flex: 1,
            minWidth: 0,
            // minHeight: 0 is load-bearing. A flex item defaults to min-height:auto, so it refuses
            // to shrink below its content: the pane grows past the row, its own scroll container
            // never engages, and the top of the document — the title and first heading — is pushed
            // out of view with no way to scroll back to it.
            minHeight: 0,
            borderRight: 1,
            borderColor: "divider",
            display: {
              xs: mobilePane === "process" ? "flex" : "none",
              md: "flex",
            },
            flexDirection: "column",
          }}
        >
          {/* Explains an unhighlighted pane. A MISSING finding has no supporting evidence by
              definition (§17.3), so nothing lights up — without this the viewer looks broken, and a
              reviewer cannot tell "found nothing" from "failed to load". */}
          {selectedFinding && !hasSupportingProcessEvidence && (
            <Alert severity="info" sx={{ m: 1, py: 0 }}>
              No passage in this document satisfies the requirement, so nothing is highlighted.
              {nearestPassages.length > 0 && (
                <>
                  {" "}The closest passage considered was{" "}
                  <Box component="span" sx={{ fontStyle: "italic" }}>
                    “{nearestPassages[0]?.text?.slice(0, 90)}
                    {(nearestPassages[0]?.text?.length ?? 0) > 90 ? "…" : ""}”
                  </Box>
                  {nearestPassages[0]?.page ? ` (page ${nearestPassages[0].page})` : ""}.
                </>
              )}
            </Alert>
          )}
          {/* NO precision banner here, deliberately.
              It reported `highlightQuality`, which grades PDF RECTANGLE precision — and this pane
              highlights from node offsets instead. A span with a valid anchor but no rendered
              geometry is UNRESOLVED, so the banner announced "nothing is highlighted" directly above
              a correctly highlighted passage.

              A caveat that contradicts what the reader can see is worse than no caveat: it teaches
              them to discount every caveat, including the MISSING notice above, which is the one that
              carries real meaning (§17.3). The precision signal still belongs somewhere — but on a
              pane whose highlights actually derive from that geometry, which this one's do not. */}
          {processCanonical.data ? (
            <CanonicalHtmlViewer
              ref={shell.leftRef}
              document={processCanonical.data}
              evidence={evidence.data?.processEvidence ?? []}
              findingId={selectedFinding?.findingId ?? null}
              title={detail.document.filename}
              subtitle="Current process — evidence"
              zoom={shell.zoom}
              onScroll={shell.onLeftScroll}
            />
          ) : (
            <PaneStatus
              loading={processCanonical.isLoading}
              message="The parsed text for this document is not available yet."
            />
          )}
        </Box>

        <Box
          sx={{
            flex: 1,
            minWidth: 0,
            // See the process pane: without this the pane cannot shrink to the row and its top
            // scrolls out of reach.
            minHeight: 0,
            display: {
              xs: mobilePane === "standard" ? "flex" : "none",
              md: "flex",
            },
            flexDirection: "column",
          }}
        >
          {standardCanonical.data ? (
            <CanonicalHtmlViewer
              ref={shell.rightRef}
              document={standardCanonical.data}
              evidence={evidence.data?.standardEvidence ?? []}
              findingId={selectedFinding?.findingId ?? null}
              title={activeReference?.standardTitle ?? "Standard"}
              subtitle={`Standard v${activeReference?.standardVersion ?? ""} — the requirement`}
              zoom={shell.zoom}
              onScroll={shell.onRightScroll}
              role="standard"
            />
          ) : (
            <PaneStatus
              loading={standardCanonical.isLoading}
              message="The parsed text for this standard is not available yet."
            />
          )}
        </Box>
      </Stack>

      {detail.references.length > 1 && (
        <Stack direction="row" spacing={1} sx={{ px: 2, py: 1, borderTop: 1, borderColor: "divider" }}>
          {detail.references.map((reference, index) => (
            <Typography
              key={reference.standardVersionId}
              variant="caption"
              onClick={() => {
                setActiveReferenceIndex(index);
                // Clear the selection: a finding belongs to one standard, and keeping it selected
                // across a switch would highlight evidence from the wrong document.
                setSelectedFinding(null);
              }}
              sx={{
                cursor: "pointer",
                fontWeight: index === activeReferenceIndex ? 600 : 400,
              }}
            >
              {reference.standardKey} v{reference.standardVersion}
            </Typography>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

