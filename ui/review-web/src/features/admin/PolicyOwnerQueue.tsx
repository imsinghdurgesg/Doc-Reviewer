import { useQuery } from "@tanstack/react-query";
import {
  Alert, Box, Chip, CircularProgress, Stack, Tooltip, Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Applicability questions awaiting a policy owner. Architecture §12.5, §17.6.
 *
 * <strong>Why these are shown rather than decided.</strong> Each is a condition where a reviewer looked
 * and could not settle whether the rule limits which documents a standard governs. Recording a
 * defensible interpretation as though it were certain is the failure this avoids — so they stay open,
 * resolve to UNKNOWN at runtime, and cannot exclude anything.
 *
 * <strong>Nothing here blocks a review.</strong> Every queued condition is still being compared and
 * flagged. The queue makes the uncertainty actionable instead of leaving it to be rediscovered by
 * whoever next reads a finding.
 */

interface OpenQuestion {
  conditionText: string;
  occurrences: number;
  standardVersionIds: readonly string[];
  requirementIds: readonly string[];
  /** The clauses the phrase came from — usually the only way to judge a truncated fragment. */
  requirementTexts: readonly string[];
  extractedRoles: readonly string[];
  reviewerNotes: string | null;
  reviewBatchId: string | null;
  inconsistentlyExtracted: boolean;
  truncated: boolean;
}

export function PolicyOwnerQueue() {
  const queue = useQuery({
    queryKey: ["admin", "policy-owner-queue"],
    queryFn: () => apiClient.get<readonly OpenQuestion[]>("/admin/standards/policy-owner-queue"),
  });

  const questions = queue.data ?? [];

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography sx={overlineLabel}>Awaiting a policy owner</Typography>
        <Typography sx={{ fontSize: 13, color: "#5c6a63", mt: 0.5, maxWidth: 800 }}>
          Conditions where a reviewer could not settle whether the rule limits which documents the
          standard governs. Each is still compared and flagged — none can mark a requirement as not
          applicable. They stay here until someone who owns the policy rules on them.
        </Typography>
      </Box>

      {queue.isLoading && (
        <Stack sx={{ alignItems: "center", py: 4 }}>
          <CircularProgress size={24} />
        </Stack>
      )}

      {!queue.isLoading && questions.length === 0 && (
        <Alert severity="success">
          No open applicability questions. Every reviewed condition has a settled role.
        </Alert>
      )}

      {questions.map((q, index) => (
        <Box
          key={`${q.conditionText}-${index}`}
          sx={{ border: `1px solid ${palette.border}`, borderRadius: 1, p: 2 }}
        >
          <Stack direction="row" spacing={0.75} useFlexGap
            sx={{ alignItems: "center", flexWrap: "wrap", mb: 1 }}>
            <Chip size="small" color="warning" label="awaiting policy owner" />
            <Chip size="small" variant="outlined"
              label={q.occurrences === 1 ? "1 occurrence" : `${q.occurrences} occurrences`} />
            {/*
              A phrase the extractor classified inconsistently is one its opinion should not be trusted
              on. Surfaced so a policy owner knows the model is not a tiebreaker here.
            */}
            {q.inconsistentlyExtracted && (
              <Tooltip title={`Extracted as: ${q.extractedRoles.join(", ")}`}>
                <Chip size="small" color="warning" variant="outlined"
                  label="extracted inconsistently" />
              </Tooltip>
            )}
            {/*
              Points at a different fix. If the phrase is a fragment, the answer is usually to repair
              extraction rather than to rule on words the standard never wrote that way.
            */}
            {q.truncated && (
              <Tooltip title="Looks like an extraction fragment — check the clause before ruling">
                <Chip size="small" variant="outlined" label="possibly truncated" />
              </Tooltip>
            )}
          </Stack>

          {/* The phrase as extracted, verbatim. */}
          <Typography sx={{ fontSize: 14, fontWeight: 600, mb: 0.5 }}>
            “{q.conditionText}”
          </Typography>

          {q.reviewerNotes && (
            <Typography sx={{ fontSize: 12.5, color: "#5c6a63", mb: 1 }}>
              {q.reviewerNotes}
            </Typography>
          )}

          {/*
            THE EVIDENCE, and the reason this panel is worth having. "When it is" is unjudgeable alone;
            beside "When the change is a material security or privacy change..." the answer is obvious.
            A queue without the clause would invite exactly the guess it exists to prevent.
          */}
          {q.requirementTexts.length > 0 && (
            <Box sx={{ mt: 1 }}>
              <Typography sx={{ ...overlineLabel, fontSize: 9, mb: 0.5 }}>
                In context
              </Typography>
              {q.requirementTexts.map((text, i) => (
                <Typography
                  key={i}
                  sx={{
                    fontSize: 12.5, lineHeight: 1.55, color: "#3d4a44",
                    borderLeft: `2px solid ${palette.border}`, pl: 1.5, mb: 0.75,
                  }}
                >
                  {text}
                </Typography>
              ))}
            </Box>
          )}

          <Stack direction="row" spacing={1} sx={{ mt: 1.5, flexWrap: "wrap" }} useFlexGap>
            <Typography variant="caption" sx={{ color: "text.secondary" }}>
              {q.requirementIds.join(", ")}
            </Typography>
            <Typography variant="caption" sx={{ color: "text.secondary" }}>
              · {q.standardVersionIds.length === 1
                ? "1 standard version"
                : `${q.standardVersionIds.length} standard versions`}
            </Typography>
            {q.reviewBatchId && (
              <Typography variant="caption" sx={{ color: "text.secondary" }}>
                · {q.reviewBatchId}
              </Typography>
            )}
          </Stack>
        </Box>
      ))}

      {questions.length > 0 && (
        <Alert severity="info">
          To resolve these, export the review file, set <strong>reviewer_role</strong> (and{" "}
          <strong>reviewer_exclusion</strong> only if a scope gate should genuinely exclude), then
          re-import as a new batch. Decisions apply to the next comparison; completed comparisons keep
          the decision that ran.
        </Alert>
      )}
    </Stack>
  );
}
