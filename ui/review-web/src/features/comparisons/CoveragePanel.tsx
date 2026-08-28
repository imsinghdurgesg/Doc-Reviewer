import { useState } from "react";
import {
  Alert, Box, Button, Chip, CircularProgress, Collapse, Divider, Stack, Typography,
} from "@mui/material";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import {
  type CoverageEntry,
  type CoverageSummary,
  coverageReconciles,
  coverageStateLabel,
  isCoverageGap,
} from "@/models/comparison";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Requirement coverage for one comparison. Architecture §17.6, §18.4.
 *
 * <strong>Why this exists.</strong> The platform already knew exactly which requirements went
 * unmeasured — the ledger decides COMPLETED versus PARTIALLY_COMPLETED — and then discarded it. A
 * reviewer was told "partially completed" with no way to learn WHICH controls were missing, so the
 * warning was unactionable: they could not judge whether the gap mattered.
 *
 * <strong>The four categories are not interchangeable.</strong> "Not applicable" is a decision, "not
 * evaluated" is the absence of one, and "applicability review required" is a decision nobody has made
 * yet. Collapsing them into "N of M evaluated" hides the only distinction that tells a reviewer what to
 * do next.
 */

/**
 * One count line.
 *
 * `indent` marks a child of the line above it. The nesting is the whole point: "compared 3" with
 * "applicability review required 1" indented beneath reads as a subset, while the same two numbers
 * flush-left read as separate outcomes a reader would add together.
 */
function CountRow({ label, value, emphasis, indent, strong }: {
  label: string; value: number; emphasis?: "gap" | "review"; indent?: boolean; strong?: boolean;
}) {
  return (
    <Stack direction="row"
      sx={{ justifyContent: "space-between", alignItems: "baseline", py: 0.35, pl: indent ? 2 : 0 }}>
      <Typography sx={{ fontSize: 13, color: indent ? "#5c6a63" : "#3d4a44",
                        fontWeight: strong ? 600 : 400 }}>
        {indent ? "└ " : ""}{label}
      </Typography>
      <Typography
        sx={{
          fontSize: 13,
          fontWeight: strong ? 700 : value > 0 && emphasis ? 700 : 500,
          // Colour only when it is non-zero AND needs attention. A zero in an alarm colour trains
          // reviewers to ignore the colour.
          color: value > 0 && emphasis === "gap" ? "#a8323a"
            : value > 0 && emphasis === "review" ? "#8a6d15"
            : "#3d4a44",
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {value}
      </Typography>
    </Stack>
  );
}

/** One requirement that was not plainly evaluated, expandable to its reason. */
function EntryRow({ entry, onRetry, retrying }: {
  entry: CoverageEntry;
  /**
   * Re-evaluates just this requirement. Absent for entries where retrying is meaningless — a
   * NOT_APPLICABLE row is a decision, not a failure, and offering to retry it would suggest the
   * decision was a fault.
   */
  onRetry?: (() => void) | undefined;
  retrying?: boolean | undefined;
}) {
  const [open, setOpen] = useState(false);
  const gap = isCoverageGap(entry);

  return (
    <Box sx={{ borderTop: `1px solid ${palette.border}` }}>
      <Box
        component="button"
        onClick={() => setOpen((v) => !v)}
        sx={{
          width: "100%", textAlign: "left", cursor: "pointer", background: "none",
          border: 0, font: "inherit", color: "inherit", p: 1, display: "flex",
          gap: 1, alignItems: "center",
          "&:hover": { bgcolor: palette.chrome },
        }}
      >
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? "rotate(0deg)" : "rotate(-90deg)", transition: "transform 120ms",
                color: palette.muted, flexShrink: 0 }}
        />
        <Typography sx={{ fontSize: 12.5, fontWeight: 600, whiteSpace: "nowrap" }}>
          {entry.requirementId}
        </Typography>
        <Typography sx={{ fontSize: 12.5, color: "#5c6a63", flex: 1, minWidth: 0 }} noWrap>
          {entry.requirementTitle ?? ""}
        </Typography>
        <Chip
          size="small"
          variant={gap ? "filled" : "outlined"}
          color={gap ? "error" : entry.state === "UNCERTAIN_APPLICABILITY" ? "warning" : "default"}
          label={coverageStateLabel(entry.state)}
        />
      </Box>

      <Collapse in={open} unmountOnExit>
        <Box sx={{ px: 1, pb: 1.5, pl: 4.5 }}>
          {entry.reason && (
            <>
              <Typography sx={{ ...overlineLabel, fontSize: 9, mb: 0.5 }}>Reason</Typography>
              <Typography sx={{ fontSize: 12.5, lineHeight: 1.55, color: "#3d4a44" }}>
                {entry.reason}
              </Typography>
            </>
          )}
          {/*
            Stated explicitly for a gap. A reviewer reading a findings list has no way to know a
            requirement is absent from it — the absence looks exactly like compliance, which is the
            misreading §17.6 exists to prevent.
          */}
          {gap && (
            <Stack direction="row" spacing={1.5}
              sx={{ mt: 1, alignItems: "center", flexWrap: "wrap" }}>
              <Typography sx={{ fontSize: 12.5, fontStyle: "italic", color: "#a8323a" }}>
                This requirement was not included in the comparison conclusion.
              </Typography>
              {/* Retries THIS requirement only — one model call rather than the whole comparison.
                  A rejected verdict is usually a per-call inconsistency, so the same requirement
                  often succeeds on a second attempt. */}
              {onRetry && (
                <Button size="small" variant="outlined" disabled={retrying} onClick={onRetry}>
                  {retrying ? "Retrying…" : "Retry this requirement"}
                </Button>
              )}
            </Stack>
          )}
          {entry.state === "UNCERTAIN_APPLICABILITY" && (
            <Typography sx={{ fontSize: 12.5, mt: 1, color: "#5c6a63" }}>
              Compared and included — confirm whether this requirement governs your document.
            </Typography>
          )}
          {entry.state === "SKIPPED" && (
            <Typography sx={{ fontSize: 12.5, mt: 1, color: "#5c6a63" }}>
              Excluded from the conclusion because it does not apply. The control remains visible here.
            </Typography>
          )}
        </Box>
      </Collapse>
    </Box>
  );
}

export function CoveragePanel({ coverage, exempt, inProgress, onRetry, retryingId }: {
  coverage: CoverageSummary | null | undefined;
  /** True when this comparison predates coverage tracking. Server-stated, never inferred. */
  exempt?: boolean | undefined;
  /** True while the comparison is still running, so absent coverage is expected rather than wrong. */
  inProgress?: boolean | undefined;
  /** Re-evaluates one requirement. Omitted where retrying is not offered. */
  onRetry?: ((requirementId: string) => void) | undefined;
  /** The requirement currently being retried, so only its own button shows progress. */
  retryingId?: string | null | undefined;
}) {
  // Opens itself when there is a gap or an unresolved scope question, so the case that matters is not
  // hidden behind an interaction. Otherwise closed: the evidence panes are what a reviewer came for.
  const needsAttention = Boolean(
    coverage && (coverage.notEvaluated > 0 || coverage.applicabilityReviewRequired > 0),
  );
  const [open, setOpen] = useState(needsAttention);

  // THREE DISTINCT ABSENCES, and conflating any two of them misinforms the reviewer.
  //
  // Absent is never "zero gaps": rendering a missing ledger as a clean summary would assert coverage
  // nobody measured. But the reasons a ledger is missing are not interchangeable —
  //
  //   in progress  the ledger is written when the run finishes, so there is nothing to show YET
  //   exempt       the review predates coverage tracking, so there never will be
  //   neither      the review should have coverage and does not, which IS a defect
  //
  // Only the last is alarming. Treating an in-flight comparison as the last one told a reviewer their
  // running review was broken — the same mistake `unevaluatedRequirements` already guards against,
  // where "36 of 52 could not be evaluated" mid-run reads as failure rather than progress.
  if (!coverage) {
    if (inProgress) {
      return (
        <Box sx={{ border: `1px solid ${palette.border}`, borderRadius: 1, p: 2 }}>
          <Typography sx={overlineLabel}>Requirement coverage</Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", mt: 1 }}>
            <CircularProgress size={14} />
            <Typography sx={{ fontSize: 12.5, color: "#5c6a63" }}>
              Recorded when the review finishes.
            </Typography>
          </Stack>
        </Box>
      );
    }
    return (
      <Box sx={{ border: `1px solid ${palette.border}`, borderRadius: 1, p: 2 }}>
        <Typography sx={overlineLabel}>Requirement coverage</Typography>
        {exempt ? (
          <Typography sx={{ fontSize: 12.5, color: "#5c6a63", mt: 0.5 }}>
            Not recorded for this comparison. This review ran before coverage tracking existed, so
            which requirements were evaluated cannot be confirmed.
          </Typography>
        ) : (
          <Alert severity="error" sx={{ fontSize: 12.5, mt: 1 }}>
            Coverage is missing for a review that should have it. Do not rely on this comparison's
            conclusion until it has been rerun.
          </Alert>
        )}
      </Box>
    );
  }

  return (
    <Box sx={{ border: `1px solid ${palette.border}`, borderRadius: 1 }}>
      {/* COLLAPSED BY DEFAULT, unless something needs attention.
          Expanded, this panel is ~9 rows plus a per-requirement list, which pushed the side-by-side
          evidence view off screen — and the evidence is what a reviewer came for. The one-line
          summary carries the whole claim; the breakdown is one click away.

          It opens itself when there is a gap or an unresolved applicability question, so the case
          that matters is never behind an interaction the reviewer has to discover. */}
      <Box
        component="button"
        onClick={() => setOpen((v) => !v)}
        sx={{
          width: "100%", textAlign: "left", cursor: "pointer", background: "none", border: 0,
          font: "inherit", color: "inherit", p: 2, display: "flex", gap: 1, alignItems: "center",
          "&:hover": { bgcolor: palette.chrome },
        }}
      >
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? "rotate(0deg)" : "rotate(-90deg)", transition: "transform 120ms",
                color: palette.muted, flexShrink: 0 }}
        />
        <Typography sx={overlineLabel}>Requirement coverage</Typography>
        {/* The denominator FIRST, as the summary bar does: "186 compared" alone is meaningless
            without knowing how many were expected. */}
        <Typography sx={{ fontSize: 12.5, color: "#5c6a63" }}>
          {coverage.compared} of {coverage.totalRequirements} compared
          {coverage.notApplicable > 0 && ` · ${coverage.notApplicable} not applicable`}
        </Typography>
        <Box sx={{ flex: 1 }} />
        {coverage.applicabilityReviewRequired > 0 && (
          <Chip size="small" color="warning" variant="outlined"
            label={`${coverage.applicabilityReviewRequired} scope unclear`} />
        )}
        {coverage.notEvaluated > 0 && (
          <Chip size="small" color="error"
            label={`${coverage.notEvaluated} not evaluated`} />
        )}
      </Box>

      <Collapse in={open} unmountOnExit>
      <Box sx={{ px: 2, pb: 2 }}>
        <Stack>
          <CountRow label="Total requirements" value={coverage.totalRequirements} strong />

          {/* Compared, with its two components indented beneath it. The three disjoint categories —
              compared, not applicable, not evaluated — partition the total exactly once. */}
          <Box sx={{ mt: 1 }}>
            <CountRow label="Compared" value={coverage.compared} />
            <CountRow label="Applicability confirmed"
              value={coverage.applicabilityConfirmed} indent />
            <CountRow label="Applicability review required"
              value={coverage.applicabilityReviewRequired} indent emphasis="review" />
          </Box>

          <Box sx={{ mt: 1 }}>
            <CountRow label="Not applicable" value={coverage.notApplicable} />
            <CountRow label="Not evaluated" value={coverage.notEvaluated} emphasis="gap" />
          </Box>
        </Stack>

        {/* If the parts do not account for the whole, say so rather than render numbers a reviewer
            would reasonably trust. Checked client-side too: the server logs it, but only the reader
            can decide whether to act on a summary that does not add up. */}
        {!coverageReconciles(coverage) && (
          <Alert severity="error" sx={{ fontSize: 12.5, mt: 1.5 }}>
            These counts do not account for every requirement. Treat the coverage figures as
            unreliable and rerun the comparison.
          </Alert>
        )}
      </Box>

      {/*
        Only for a real gap. A complete review needs no reassurance banner, and one on every
        comparison would dilute the case where it means something.
      */}
      {/* NOT an alert here. The "Not evaluated" row is already red and non-zero, and each affected
          requirement states "not included in the comparison conclusion" when expanded. A banner
          restating the same count was the fourth copy of one sentence on this screen. */}

      {coverage.uncovered.length > 0 && (
        <>
          <Divider />
          <Box sx={{ px: 2, pt: 1.5 }}>
            <Typography sx={{ ...overlineLabel, fontSize: 9 }}>
              Requirements not plainly evaluated
            </Typography>
          </Box>
          <Box sx={{ mt: 0.5 }}>
            {/*
              Gaps first. A reviewer scanning this list should meet the unmeasured requirements before
              the legitimate exclusions, because only the former can invalidate the conclusion.
            */}
            {[...coverage.uncovered]
              .sort((a, b) => Number(isCoverageGap(b)) - Number(isCoverageGap(a)))
              .map((entry) => (
                <EntryRow
                  key={`${entry.standardVersionId}-${entry.requirementId}`}
                  entry={entry}
                  // Only for a genuine gap. NOT_APPLICABLE and UNCERTAIN are decisions, and retrying
                  // them would imply the decision was a failure.
                  onRetry={onRetry && isCoverageGap(entry)
                    ? () => onRetry(entry.requirementId)
                    : undefined}
                  retrying={retryingId === entry.requirementId}
                />
              ))}
          </Box>
        </>
      )}
      </Collapse>
    </Box>
  );
}
