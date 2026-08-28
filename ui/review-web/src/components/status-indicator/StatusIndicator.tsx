import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorIcon from "@mui/icons-material/Error";
import BlockIcon from "@mui/icons-material/Block";
import HelpIcon from "@mui/icons-material/Help";
import WarningIcon from "@mui/icons-material/Warning";
import { Chip, Tooltip } from "@mui/material";
import type { ComparisonStatus, Severity } from "@/models/comparison";
import { palette } from "@/app/theme";

/**
 * Status and severity indicators. Architecture §17.
 *
 * **Each status carries an icon, not only a colour.** Colour alone excludes anyone with reduced
 * colour discrimination, and these five statuses drive real decisions — a reviewer who cannot
 * distinguish CONTRADICTED from ALIGNED cannot use the tool at all.
 *
 * The tooltips state what each status MEANS rather than repeating its name. The distinction between
 * MISSING and INSUFFICIENT_EVIDENCE (§17.6) is the one users get wrong, and it is the one that
 * changes what they should do next.
 *
 * Tinted rather than outlined, matching the reference implementation: a findings list is mostly text,
 * and a filled chip is what makes status scannable down a long column. The tint pairs a light
 * background with a darker foreground so the icon stays legible on it.
 */

/** Background/foreground pairs per status. Three tints, not five — MISSING and CONTRADICTED share. */
const STATUS_TINT: Record<ComparisonStatus, { bg: string; fg: string }> = {
  ALIGNED: palette.statusAligned,
  PARTIAL: palette.statusMedium,
  MISSING: palette.statusHigh,
  CONTRADICTED: palette.statusHigh,
  INSUFFICIENT_EVIDENCE: { bg: "#e6e9ec", fg: "#5b6670" },
};

const STATUS_META: Record<
  ComparisonStatus,
  { label: string; color: "success" | "warning" | "error" | "info" | "default"; help: string }
> = {
  ALIGNED: {
    label: "Aligned",
    color: "success",
    help: "The process satisfies this requirement. Exact wording is not required.",
  },
  PARTIAL: {
    label: "Partial",
    color: "warning",
    help: "Some elements are present, but the process is weaker, incomplete, or mistimed.",
  },
  MISSING: {
    label: "Missing",
    color: "error",
    help: "The document was adequately searched and this obligation is absent.",
  },
  CONTRADICTED: {
    label: "Contradicted",
    color: "error",
    help: "The process explicitly conflicts with the requirement. Read this first.",
  },
  INSUFFICIENT_EVIDENCE: {
    label: "Insufficient evidence",
    color: "info",
    // The critical distinction: NOT a gap. Acting on it as one asserts something never established.
    help:
      "Not a gap — we could not determine either way. Scope was unclear, a referenced procedure "
      + "was not supplied, or parsing was incomplete.",
  },
};

const STATUS_ICON: Record<ComparisonStatus, React.ReactElement> = {
  ALIGNED: <CheckCircleIcon fontSize="small" />,
  PARTIAL: <WarningIcon fontSize="small" />,
  MISSING: <ErrorIcon fontSize="small" />,
  CONTRADICTED: <BlockIcon fontSize="small" />,
  INSUFFICIENT_EVIDENCE: <HelpIcon fontSize="small" />,
};

export function StatusIndicator({ status }: { status: ComparisonStatus }) {
  const meta = STATUS_META[status];
  return (
    <Tooltip title={meta.help}>
      <Chip
        size="small"
        icon={STATUS_ICON[status]}
        label={meta.label}
        sx={{
          bgcolor: STATUS_TINT[status].bg,
          color: STATUS_TINT[status].fg,
          fontWeight: 600,
          fontSize: 11,
          borderRadius: "3px",
          // Inherits the chip's foreground so the icon is not left at MUI's default grey.
          "& .MuiChip-icon": { color: "inherit", fontSize: 15 },
        }}
      />
    </Tooltip>
  );
}

const SEVERITY_TINT: Record<Severity, { bg: string; fg: string }> = {
  HIGH: palette.statusHigh,
  MEDIUM: palette.statusMedium,
  LOW: { bg: "#e6e9ec", fg: "#5b6670" },
  INFORMATIONAL: { bg: "#eef0ed", fg: "#77837c" },
};

export function SeverityIndicator({ severity }: { severity: Severity }) {
  return (
    <Tooltip title="Severity comes from the standard's own metadata or administrator rules, never from the model.">
      <Chip
        size="small"
        label={severity}
        sx={{
          bgcolor: SEVERITY_TINT[severity].bg,
          color: SEVERITY_TINT[severity].fg,
          fontWeight: 700,
          fontSize: 10,
          letterSpacing: "0.4px",
          borderRadius: "3px",
        }}
      />
    </Tooltip>
  );
}
