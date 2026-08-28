import { useMemo, useState } from "react";
import {
  Alert,
  Box,
  Chip,
  Collapse,
  Divider,
  IconButton,
  LinearProgress,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";
import { overlineLabel, palette } from "@/app/theme";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import { isComparisonRunning, isIncompleteReview } from "@/models/comparison";
import { AssessmentFocus } from "./AssessmentFocus";
import { CoveragePanel } from "@/features/comparisons/CoveragePanel";
import type { ComparisonDetail, ComparisonStatus, Finding } from "@/models/comparison";

/**
 * Coverage and outcome summary above the review panes. Architecture §17, §18.4, §19.2.
 *
 * Exists because the single most dangerous way to misread this tool is to treat a short findings list
 * as good news. Three gaps out of three requirements evaluated, and three gaps out of two hundred,
 * look identical in a list — so the DENOMINATOR is stated first, before any count of problems.
 *
 * It also separates the two things a reviewer must not conflate:
 *
 *   - "requirements needing attention" — real gaps to act on
 *   - "requirements we could not judge" — INSUFFICIENT_EVIDENCE, which is NOT a gap (§17.5).
 *     Counting those as findings would assert an absence that was never established.
 */
export interface ComparisonSummaryBarProps {
  detail: ComparisonDetail;
  findings: readonly Finding[];
}

/** Statuses that represent work for a human, as opposed to a clean pass or an open question. */
const ACTIONABLE: readonly ComparisonStatus[] = ["CONTRADICTED", "PARTIAL", "MISSING"];

export function ComparisonSummaryBar({ detail, findings }: ComparisonSummaryBarProps) {
  const [legendOpen, setLegendOpen] = useState(false);

  const counts = useMemo(() => {
    const by = (s: ComparisonStatus) => findings.filter((f) => f.status === s).length;
    return {
      aligned: by("ALIGNED"),
      partial: by("PARTIAL"),
      missing: by("MISSING"),
      contradicted: by("CONTRADICTED"),
      unclear: by("INSUFFICIENT_EVIDENCE"),
      actionable: findings.filter((f) => ACTIONABLE.includes(f.status)).length,
      needsReview: findings.filter((f) => f.needsHumanReview).length,
    };
  }, [findings]);

  const evaluated = detail.evaluatedRequirements ?? findings.length;
  const total = detail.totalRequirements ?? findings.length;
  // Guard against a zero denominator: a comparison that failed before loading requirements would
  // otherwise render NaN%.
  const coverage = total > 0 ? Math.round((evaluated / total) * 100) : 0;
  // ONLY once the run has finished.
  //
  // While a comparison is in flight, evaluated < total simply means the remaining requirements have
  // not been reached yet — and reporting "36 of 52 could not be evaluated" mid-run tells a reviewer
  // their review has failed when it is merely 16 requirements in. Before progress was reported the
  // counters were null during a run, so this could not fire; now that they advance live, the terminal
  // check is what distinguishes "not yet attempted" from "attempted and rejected".
  const incomplete = isIncompleteReview(detail);

  return (
    <Box
      sx={{
        px: 3,
        py: 1.75,
        borderBottom: 1,
        borderColor: "divider",
        // Slightly off-white so the chrome reads as a toolbar rather than as content.
        bgcolor: palette.chrome,
      }}
    >
      <Stack direction="row" spacing={3} sx={{ alignItems: "center", flexWrap: "wrap", rowGap: 1 }}>
        {/* Coverage FIRST. Everything else is meaningless without it. */}
        <Stack sx={{ minWidth: 190 }}>
          <Typography sx={overlineLabel}>
            Requirements evaluated
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: "baseline" }}>
            <Typography sx={{ fontSize: 20, fontWeight: 700, lineHeight: 1.2, mt: 0.25 }}>
              {evaluated}
              <Typography component="span" variant="body2" color="text.secondary">
                {" "}/ {total}
              </Typography>
            </Typography>
            {incomplete && (
              <Tooltip title="Some requirements could not be evaluated. The findings shown are valid, but this review is not complete (§18.4).">
                <Chip size="small" color="warning" label={`${coverage}%`} />
              </Tooltip>
            )}
          </Stack>
          <LinearProgress
            variant="determinate"
            value={coverage}
            color={incomplete ? "warning" : "success"}
            sx={{ mt: 0.5, height: 4, borderRadius: 2 }}
          />
        </Stack>

        <Divider orientation="vertical" flexItem />

        {/* The headline number a reviewer acts on. Deliberately excludes ALIGNED and
            INSUFFICIENT_EVIDENCE — neither is a gap to fix. */}
        <Stack sx={{ pl: 2.5, borderLeft: 1, borderColor: "divider" }}>
          <Typography sx={overlineLabel}>
            Needs attention
          </Typography>
          <Typography
            sx={{ fontSize: 20, fontWeight: 700, mt: 0.25 }}
            color={counts.actionable > 0 ? "error.main" : "success.main"}
          >
            {counts.actionable}
          </Typography>
        </Stack>

        <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap" }}>
          {counts.contradicted > 0 && (
            <Chip size="small" label={`${counts.contradicted} contradicted`}
              sx={{ bgcolor: palette.statusHigh.bg, color: palette.statusHigh.fg,
                    fontWeight: 700, fontSize: 11, borderRadius: "3px" }} />
          )}
          {counts.missing > 0 && (
            <Chip size="small" variant="outlined" label={`${counts.missing} missing`}
              sx={{ color: palette.statusHigh.fg, borderColor: palette.statusHigh.fg,
                    fontWeight: 600, fontSize: 11, borderRadius: "3px" }} />
          )}
          {counts.partial > 0 && (
            <Chip size="small" label={`${counts.partial} partial`}
              sx={{ bgcolor: palette.statusMedium.bg, color: palette.statusMedium.fg,
                    fontWeight: 700, fontSize: 11, borderRadius: "3px" }} />
          )}
          {counts.aligned > 0 && (
            <Chip size="small" variant="outlined" label={`${counts.aligned} aligned`}
              sx={{ color: palette.statusAligned.fg, borderColor: palette.statusAligned.fg,
                    fontWeight: 600, fontSize: 11, borderRadius: "3px" }} />
          )}
          {/* Visually distinct from the gap chips: this is an open question, not a failure. */}
          {counts.unclear > 0 && (
            <Tooltip title="Not gaps. We could not determine either way — scope was unclear, a referenced document was not supplied, or parsing was incomplete.">
              <Chip size="small" color="info" variant="outlined"
                label={`${counts.unclear} unclear`} />
            </Tooltip>
          )}
          {counts.needsReview > 0 && (
            <Chip size="small" color="warning" variant="outlined"
              label={`${counts.needsReview} flagged for review`} />
          )}
        </Stack>

        <Box sx={{ flex: 1 }} />

        <Tooltip title="What the statuses mean">
          <IconButton size="small" onClick={() => setLegendOpen((v) => !v)}>
            <InfoOutlinedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Stack>

      {/* Stated inline rather than left to a tooltip: a partial review presented as a complete one is
          the failure mode §18.4 exists to prevent. */}
      {incomplete && (
        <Alert severity="warning" sx={{ mt: 1, py: 0 }}>
          {total - evaluated} of {total} requirements could not be evaluated. Treat this as an
          incomplete review — an absent finding here does not mean compliance.
        </Alert>
      )}

      {counts.actionable === 0 && !incomplete && findings.length > 0 && (
        <Alert severity="success" sx={{ mt: 1, py: 0 }}>
          Every evaluated requirement is satisfied. {counts.unclear > 0
            ? `${counts.unclear} could not be determined and still need a human view.`
            : ""}
        </Alert>
      )}

      <Collapse in={legendOpen}>
        <Box sx={{ mt: 1.5, pt: 1.5, borderTop: 1, borderColor: "divider" }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: "block", mb: 1 }}>
            Read right-to-left: the <strong>standard</strong> states the requirement, the{" "}
            <strong>current process</strong> is the evidence for or against it. Highlighted text is
            loaded from the stored source document — the model returns only span references, never
            quotations.
          </Typography>
          <Stack spacing={0.75}>
            {LEGEND.map((row) => (
              <Stack key={row.label} direction="row" spacing={1} sx={{ alignItems: "flex-start" }}>
                <Chip size="small" color={row.color} variant={row.variant} label={row.label}
                  sx={{ minWidth: 132 }} />
                <Typography variant="caption" color="text.secondary" sx={{ flex: 1 }}>
                  {row.help}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </Box>
      </Collapse>

      {/* The focus the review ran under (§19.2). Shown once per review, directly below the counts,
          because it qualifies exactly those counts — a reader seeing "16 missing" needs to know
          whether the search was deliberately narrowed before drawing a conclusion from it. */}
      <AssessmentFocus guidance={detail.comparisonGuidance} variant="inline" />

      {/* Coverage AFTER the status counts, because it qualifies them: "16 missing" means something
          different when three requirements were never evaluated at all. §17.6 requires the untested
          ones to be visible rather than absorbed into the finding totals. */}
      <Box sx={{ mt: 2 }}>
        {/* inProgress from the SAME terminal-state list the polling logic uses, so the panel and the
            poller cannot disagree about whether a review has finished. */}
        <CoveragePanel
          coverage={detail.coverage}
          exempt={detail.coverageExempt}
          inProgress={isComparisonRunning(detail)}
        />
      </Box>
    </Box>
  );
}

/**
 * Status meanings, ordered by how urgently they need a person.
 *
 * Mirrors §17. The MISSING vs INSUFFICIENT_EVIDENCE pair is spelled out most carefully because it is
 * the distinction users get wrong, and the one that changes what they should do next (§17.6).
 */
const LEGEND: readonly {
  label: string;
  color: "error" | "warning" | "success" | "info";
  variant: "filled" | "outlined";
  help: string;
}[] = [
  {
    label: "Contradicted",
    color: "error",
    variant: "filled",
    help: "The process explicitly permits what the standard forbids. Someone wrote this down on purpose — read these first.",
  },
  {
    label: "Missing",
    color: "error",
    variant: "outlined",
    help: "In scope, searched, and genuinely absent. Only used when the document was complete enough to support that conclusion.",
  },
  {
    label: "Partial",
    color: "warning",
    variant: "filled",
    help: "Present but weaker, incomplete, or mistimed. Expand the atomic clauses to see which specific element failed — this is where a keyword search would wrongly report a match.",
  },
  {
    label: "Aligned",
    color: "success",
    variant: "outlined",
    help: "Satisfied. Wording need not match the standard; materially equivalent language counts. Retained in the list so the scope of the review is visible.",
  },
  {
    label: "Insufficient evidence",
    color: "info",
    variant: "outlined",
    help: "NOT a gap. Scope was unclear, parsing was incomplete, or the process points at a document that was not supplied. Acting on this as a gap asserts something never established.",
  },
];
