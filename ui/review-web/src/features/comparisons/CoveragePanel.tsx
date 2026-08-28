import { useState } from "react";
import {
  Alert, Box, Chip, Collapse, Divider, Stack, Typography,
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
function EntryRow({ entry }: { entry: CoverageEntry }) {
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
            <Typography sx={{ fontSize: 12.5, mt: 1, fontStyle: "italic", color: "#a8323a" }}>
              This requirement was not included in the comparison conclusion.
            </Typography>
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

export function CoveragePanel({ coverage, exempt }: {
  coverage: CoverageSummary | null | undefined;
  /** True when this comparison predates coverage tracking. Server-stated, never inferred. */
  exempt?: boolean | undefined;
}) {
  // TWO DISTINCT ABSENCES, and conflating them is the thing to avoid.
  //
  // Absent is never "zero gaps" — rendering a missing ledger as a clean summary would assert coverage
  // nobody measured. But "this review predates coverage tracking" and "this review should have
  // coverage and does not" are also different facts, and only the first is reassuring.
  if (!coverage) {
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
      <Box sx={{ p: 2 }}>
        <Typography sx={overlineLabel}>Requirement coverage</Typography>

        <Stack sx={{ mt: 1 }}>
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
      {coverage.notEvaluated > 0 && (
        <Box sx={{ px: 2, pb: 2 }}>
          <Alert severity="warning" sx={{ fontSize: 12.5 }}>
            {coverage.notEvaluated === 1
              ? "1 requirement was not evaluated and is not reflected in the conclusion."
              : `${coverage.notEvaluated} requirements were not evaluated and are not reflected in the conclusion.`}
          </Alert>
        </Box>
      )}

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
                <EntryRow key={`${entry.standardVersionId}-${entry.requirementId}`} entry={entry} />
              ))}
          </Box>
        </>
      )}
    </Box>
  );
}
