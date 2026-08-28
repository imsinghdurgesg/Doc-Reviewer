import { useMemo, useState } from "react";
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Chip, Divider,
  List, ListItemButton, Stack, Typography,
} from "@mui/material";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import type { ComparisonStatus, Finding } from "@/models/comparison";
import { hasClauseSpecificDetail, isClauseSpecificReason } from "@/models/comparison";
import { ApplicabilityNotice } from "@/features/findings/ApplicabilityNotice";
import { SeverityIndicator, StatusIndicator } from "@/components/status-indicator/StatusIndicator";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Findings list with atomic-clause expansion. Architecture §18.6, §19.1.
 *
 * **Aligned controls are shown, not hidden** (§18.6: "retain aligned controls so users can inspect
 * the evidence and scope of the review"). A list showing only three problems gives no sense of how
 * much was actually reviewed — and a reviewer cannot tell a thorough review with three gaps from a
 * broken one that found three things and stopped.
 *
 * **Clause expansion is where a PARTIAL becomes actionable.** "Partial" alone is a shrug; the clause
 * results say approval exists but independence does not, which is the sentence someone can act on.
 */
export interface FindingsPanelProps {
  findings: readonly Finding[];
  selectedFindingId: string | null;
  onSelectFinding: (finding: Finding) => void;
}

/**
 * Display order: contradictions first, then gaps, then uncertainty, then aligned.
 *
 * Not alphabetical and not by severity. A CONTRADICTED finding means the documented process
 * explicitly permits what the standard forbids — that is qualitatively worse than an omission,
 * because someone wrote it down on purpose.
 */
const STATUS_ORDER: Record<ComparisonStatus, number> = {
  CONTRADICTED: 0,
  PARTIAL: 1,
  MISSING: 2,
  INSUFFICIENT_EVIDENCE: 3,
  ALIGNED: 4,
};

export function FindingsPanel({
  findings,
  selectedFindingId,
  onSelectFinding,
}: FindingsPanelProps) {
  const [expandedClauses, setExpandedClauses] = useState<string | null>(null);

  const sorted = useMemo(
    () =>
      [...findings].sort((a, b) => {
        const byStatus = STATUS_ORDER[a.status] - STATUS_ORDER[b.status];
        return byStatus !== 0 ? byStatus : a.requirementId.localeCompare(b.requirementId);
      }),
    [findings],
  );

  const counts = useMemo(() => {
    const result = new Map<ComparisonStatus, number>();
    findings.forEach((f) => result.set(f.status, (result.get(f.status) ?? 0) + 1));
    return result;
  }, [findings]);

  if (findings.length === 0) {
    return (
      <Box sx={{ p: 2 }}>
        {/* An empty list is ambiguous — no requirements, or none evaluated? Say which. */}
        <Alert severity="info">
          No requirements were evaluated for this standard yet.
        </Alert>
      </Box>
    );
  }

  return (
    <Stack sx={{ height: "100%", overflow: "auto" }}>
      <Box sx={{ px: 2.25, pt: 2.5, pb: 1.75, borderBottom: 1, borderColor: "divider" }}>
        <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.4px" }}>
          {findings.length} requirement{findings.length === 1 ? "" : "s"} checked
        </Typography>
        {/* The click-to-navigate interaction is the point of the three-pane layout, and nothing else
            on screen advertises it — an unselected workspace otherwise looks like two static PDFs. */}
        <Typography
          variant="caption"
          sx={{ display: "block", mt: 0.5, mb: 1.5, color: palette.muted }}
        >
          Select one to highlight its evidence in both documents.
        </Typography>
        <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
          {(Object.keys(STATUS_ORDER) as ComparisonStatus[])
            .filter((status) => counts.has(status))
            .map((status) => (
              <Chip
                key={status}
                size="small"
                label={`${status.replace(/_/g, " ").toLowerCase()} ${counts.get(status)}`}
                variant="outlined"
              />
            ))}
        </Stack>
      </Box>

      <List disablePadding>
        {sorted.map((finding, index) => (
          <Box key={finding.findingId}>
            <ListItemButton
              selected={finding.findingId === selectedFindingId}
              onClick={() => onSelectFinding(finding)}
              alignItems="flex-start"
              sx={{
                display: "block",
                px: 2.25,
                py: 1.75,
                borderBottom: 1,
                borderColor: "divider",
                "&:hover": { bgcolor: palette.rowHover },
                // An inset bar rather than a full background change: the row stays readable while
                // the selection is unmistakable at a glance down a long list.
                "&.Mui-selected": {
                  bgcolor: palette.rowSelected,
                  boxShadow: `inset 3px 0 ${palette.forest}`,
                  "&:hover": { bgcolor: palette.rowSelected },
                },
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 0.5 }}>
                <Typography sx={{ ...overlineLabel, fontSize: 9 }}>
                  {String(index + 1).padStart(2, "0")}
                </Typography>
                {/* The standard's own control ID when it has one (§12.7): a reviewer who knows the
                    standard navigates by 4.2, not by a generated identifier. */}
                {finding.parentControlId && (
                  <Chip size="small" label={finding.parentControlId} variant="outlined" />
                )}
                <Typography
                  sx={{ fontSize: 14, fontWeight: 600, lineHeight: 1.35, flex: 1, minWidth: 0 }}
                  noWrap
                >
                  {finding.requirementTitle}
                </Typography>
              </Stack>

              <Stack direction="row" spacing={0.5} useFlexGap sx={{ alignItems: "center", flexWrap: "wrap" }}>
                <StatusIndicator status={finding.status} />
                <SeverityIndicator severity={finding.severity} />
                {finding.gapType && (
                  <Chip size="small" variant="outlined"
                    label={finding.gapType.replace(/_/g, " ").toLowerCase()} />
                )}
                {/* Surfaced prominently: either the model was unsure, or the deterministic roll-up
                    disagreed with it. Both are cases a person should look at. */}
                {finding.needsHumanReview && (
                  <Chip size="small" color="warning" label="needs review" />
                )}
              </Stack>

              {/* Before the compliance reason, deliberately: whether a rule governs the document at
                  all is prior to how well the document satisfies it. */}
              <ApplicabilityNotice finding={finding} />

              <Typography sx={{ mt: 1, fontSize: 12.5, lineHeight: 1.55, color: "#5c6a63" }}>
                {finding.reason}
              </Typography>

              {finding.recommendedAction && (
                <Typography
                  sx={{ mt: 1, fontSize: 12.5, lineHeight: 1.55, fontStyle: "italic",
                        color: "#5c6a63" }}
                >
                  {finding.recommendedAction}
                </Typography>
              )}

              {/* MISSING with no evidence at all is unfalsifiable. Naming the nearest passage the
                  tool DID consider lets a reviewer check whether it looked in the right place. */}
              {finding.processSpanIds.length === 0 &&
                finding.nearestProcessSpanIds.length > 0 && (
                  <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 0.5 }}>
                    Nearest related passage was considered and did not satisfy this requirement.
                  </Typography>
                )}
            </ListItemButton>

            {finding.clauseResults.length > 0 && (
              <Accordion
                disableGutters
                elevation={0}
                expanded={expandedClauses === finding.findingId}
                onChange={() =>
                  setExpandedClauses(
                    expandedClauses === finding.findingId ? null : finding.findingId,
                  )
                }
              >
                <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ minHeight: 36, px: 2 }}>
                  <Typography variant="caption">
                    {finding.clauseResults.length} atomic clause
                    {finding.clauseResults.length === 1 ? "" : "s"}
                  </Typography>
                </AccordionSummary>
                <AccordionDetails sx={{ px: 2, pt: 0 }}>
                  <Stack spacing={1}>
                    {finding.clauseResults.map((clause) => (
                      <Stack key={clause.clauseId} spacing={0.25}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                          <Typography variant="caption" sx={{ fontFamily: "monospace" }}>
                            {clause.clauseId}
                          </Typography>
                          <StatusIndicator status={clause.status} />
                          {clause.gapType && (
                            <Typography variant="caption" color="text.secondary">
                              [{clause.gapType.replaceAll("_", " ").toLowerCase()}]
                            </Typography>
                          )}
                        </Stack>
                        {/* WHAT the clause requires. Two sibling clauses of one requirement can carry
                            the same status and gap type — 5.1-a "publish" and 5.1-b "observe" were
                            both missing — and without this the rows are indistinguishable. Absent on
                            comparisons stored before the field existed. */}
                        {clause.clauseText && (
                          <Typography variant="caption" sx={{ color: "text.primary" }}>
                            {clause.clauseText}
                          </Typography>
                        )}
                        {/* Rendered ONLY when it is genuinely clause-specific.
                            Schema V1 returns one reason for the whole requirement, and it used to be
                            copied onto every clause — so a three-clause requirement showed the same
                            paragraph three times, each copy discussing all three clauses next to a
                            single clause's status. `isClauseSpecific` suppresses that for stored V1
                            results without rewriting them (§19.2). */}
                        {isClauseSpecificReason(clause, finding.clauseResults) && (
                          <Typography variant="caption" color="text.secondary">
                            {clause.reason}
                          </Typography>
                        )}
                      </Stack>
                    ))}
                  </Stack>
                  {!hasClauseSpecificDetail(finding.clauseResults) && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ display: "block", mt: 1, fontStyle: "italic" }}
                    >
                      Clause-specific explanations were not generated for this comparison. The
                      reason and evidence above apply to the requirement as a whole.
                    </Typography>
                  )}
                </AccordionDetails>
              </Accordion>
            )}
            <Divider />
          </Box>
        ))}
      </List>
    </Stack>
  );
}
