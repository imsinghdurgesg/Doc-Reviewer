import { Chip, Stack, Tooltip, Typography } from "@mui/material";
import {
  type Finding,
  findingApplicability,
  isGovernedDecision,
  needsScopeReview,
} from "@/models/comparison";

/**
 * Whether a requirement APPLIED, shown separately from whether it was satisfied. §12.5, §17.6.
 *
 * <strong>Why this is not folded into the status chip.</strong> Applicability and compliance answer
 * different questions, and collapsing them destroys information a reviewer needs. A requirement that
 * does not apply was never measured — rendering that as MISSING invents a gap the standard never
 * demanded, and as ALIGNED claims compliance nobody assessed.
 *
 * <strong>Renders nothing for the ordinary case.</strong> The overwhelming majority of requirements
 * plainly apply, and an "applies: yes" badge on every finding is noise that trains reviewers to skip
 * the row where it finally says something else.
 */
export function ApplicabilityNotice({ finding }: { finding: Finding }) {
  if (!needsScopeReview(finding)) {
    return null;
  }

  const status = findingApplicability(finding);
  const excluded = status === "NOT_APPLICABLE";

  return (
    <Stack spacing={0.5} sx={{ mt: 1 }}>
      <Stack direction="row" spacing={0.5} useFlexGap sx={{ alignItems: "center", flexWrap: "wrap" }}>
        {/*
          NOT_APPLICABLE is "default" rather than an alarm colour: it is a legitimate outcome, and a red
          badge would read as a failure. UNCERTAIN is warning-coloured because it IS an open question —
          nobody has established whether this rule governs the document.
        */}
        <Chip
          size="small"
          color={excluded ? "default" : "warning"}
          variant={excluded ? "outlined" : "filled"}
          label={excluded ? "does not apply" : "scope unclear"}
        />
        {/*
          Distinguishes a decision a person signed off from one the rule engine reached alone. Without
          it, "does not apply" looks equally authoritative either way — and an unreviewed exclusion
          deserves more scepticism than a governed one.
        */}
        {isGovernedDecision(finding) && (
          <Tooltip title={`Decided by ${finding.applicabilityDecidedBy}`}>
            <Chip size="small" variant="outlined" label="reviewed" />
          </Tooltip>
        )}
      </Stack>

      {/*
        The stated condition, verbatim from the standard. This is the reviewer's evidence: a conclusion
        with nothing to check it against is not reviewable, and an exclusion is exactly where an
        unfalsifiable claim does the most damage.
      */}
      {finding.applicabilityReason && (
        <Typography sx={{ fontSize: 12, lineHeight: 1.5, color: "#5c6a63" }}>
          {finding.applicabilityReason}
        </Typography>
      )}

      {/*
        Says plainly that the control was NOT dropped. A reviewer seeing "scope unclear" would otherwise
        reasonably assume the tool skipped it — the opposite of what happens, and the assumption that
        makes an uncertain requirement quietly go unexamined.
      */}
      {!excluded && (
        <Typography variant="caption" sx={{ color: "text.secondary" }}>
          Compared anyway — confirm whether this requirement governs your document.
        </Typography>
      )}
    </Stack>
  );
}
