import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert, Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent,
  DialogTitle, Divider, MenuItem, Stack, TextField, Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Review one standard version's applicability conditions. Architecture §12.5, §19.2.
 *
 * <strong>Opened from the version row it governs.</strong> A condition decision belongs to a specific
 * extraction of a specific standard version, so reaching it from that row carries the context with it.
 * The Admin → Applicability tab remains as the cross-standard audit view; this is where the work happens.
 *
 * <strong>Why editing here is safe.</strong> Recording a decision used to require a reviewed CSV, which
 * gave every decision a file and a hash behind it. That provenance is not discarded, it is narrowed: a
 * decision made here still records who decided, when, and under which batch id. What it cannot claim is
 * that a signed-off file supports it — so on revising a CSV-authored row the server CLEARS the file name
 * and hash rather than leaving them pointing at a file the row no longer matches.
 *
 * The one control-removing power keeps all four of its guards. Authorising exclusion is accepted only on
 * a confirmed scope gate, and the server recomputes that rather than trusting this form.
 */

/** Mirrors StandardAdminController.ConditionsResponse. */
interface ConditionsResponse {
  requirementSetVersion: string | null;
  promptVersion: string | null;
  conditions: readonly ConditionRow[];
}

/** Mirrors ConditionGovernanceClient.ConditionRow. */
interface ConditionRow {
  requirementId: string;
  conditionText: string;
  extractedRole: string;
  effectiveRole: string;
  source: string;
  canDriveNotApplicable: boolean;
  reviewerNotes: string | null;
  overrideBatchId: string | null;
  reclassified: boolean;
}

/**
 * The roles, with the plain-English gloss that decides the classification.
 *
 * Only SCOPE_GATE can exclude, and the model over-predicts it heavily — in one reviewed corpus it
 * proposed 117 and review confirmed none. So the descriptions lead with the distinction that matters:
 * does the condition say WHO the rule covers, or WHEN it fires?
 */
const ROLES: ReadonlyArray<{ value: string; label: string; hint: string }> = [
  {
    value: "SCOPE_GATE",
    label: "Scope gate",
    hint: "Limits WHICH documents the rule covers — the only role that can exclude a requirement",
  },
  {
    value: "CONDITIONAL_OBLIGATION",
    label: "Conditional obligation",
    hint: "Says WHEN the duty triggers. The rule still covers this document",
  },
  {
    value: "IMPLEMENTATION_QUALIFIER",
    label: "Implementation qualifier",
    hint: "Describes HOW to satisfy the requirement",
  },
  {
    value: "EXCEPTION_OR_WAIVER",
    label: "Exception or waiver",
    hint: "A carve-out from an otherwise applicable duty",
  },
  {
    value: "TEMPORAL_OR_SEQUENCE",
    label: "Timing or sequence",
    hint: "A deadline or an ordering constraint",
  },
  {
    value: "UNKNOWN",
    label: "Cannot decide — send to policy owner",
    hint: "Records the question as open rather than guessing. Cannot exclude",
  },
];

const ROLE_LABEL: Record<string, string> = Object.fromEntries(
  ROLES.map((r) => [r.value, r.label]),
);

interface Draft {
  reviewedRole: string;
  reviewerNotes: string;
  authoriseExclusion: boolean;
}

/** One condition, expanded for editing. */
function ConditionEditor({
  row, standardVersionId, readOnly,
}: {
  row: ConditionRow;
  standardVersionId: string;
  readOnly: boolean;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<Draft>({
    // Seeded from the EFFECTIVE role, not the extracted one: that is what governs comparisons today,
    // so it is the value a reviewer is actually amending.
    reviewedRole: row.effectiveRole,
    reviewerNotes: row.reviewerNotes ?? "",
    authoriseExclusion: row.canDriveNotApplicable,
  });
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () =>
      apiClient.post<ConditionRow>(
        `/admin/standards/versions/${standardVersionId}/conditions/review`,
        {
          requirementId: row.requirementId,
          conditionText: row.conditionText,
          reviewedRole: draft.reviewedRole,
          // UNKNOWN records an open question; anything else is a settled decision. The server maps
          // this to NEEDS_POLICY_OWNER or CONFIRMED, and neither the reviewer nor this form should be
          // choosing a review status independently of the role they picked.
          reviewStatus: draft.reviewedRole === "UNKNOWN" ? "NEEDS_POLICY_OWNER" : "CONFIRMED",
          authoriseExclusion: draft.authoriseExclusion,
          reviewerNotes: draft.reviewerNotes,
        },
      ),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["admin", "conditions"] });
      void queryClient.invalidateQueries({ queryKey: ["applicability", standardVersionId] });
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Could not save"),
  });

  // Exclusion is offered ONLY on a scope gate, matching the server rule. Showing a checkbox that the
  // server would silently ignore would tell the reviewer they had granted something they had not.
  const canOfferExclusion = draft.reviewedRole === "SCOPE_GATE";
  const noteMissing = draft.reviewerNotes.trim().length === 0;
  const changed =
    draft.reviewedRole !== row.effectiveRole
    || draft.reviewerNotes !== (row.reviewerNotes ?? "")
    || draft.authoriseExclusion !== row.canDriveNotApplicable;

  return (
    <Box sx={{ borderTop: `1px solid ${palette.border}`, py: 1.5 }}>
      <Stack direction="row" spacing={1} sx={{ mb: 0.75, alignItems: "center", flexWrap: "wrap" }}>
        <Typography sx={{ fontSize: 12.5, fontWeight: 600 }}>{row.requirementId}</Typography>
        <Chip size="small" variant="outlined"
          label={`model: ${ROLE_LABEL[row.extractedRole] ?? row.extractedRole}`} />
        {row.reclassified && (
          <Chip size="small" color="warning" variant="outlined" label="reclassified by review" />
        )}
        {row.canDriveNotApplicable && (
          <Chip size="small" color="error" label="may exclude a requirement" />
        )}
        {row.overrideBatchId && (
          <Chip size="small" variant="outlined" label={row.overrideBatchId}
            sx={{ height: 20, fontSize: 11 }} />
        )}
      </Stack>

      <Typography sx={{ fontSize: 12.5, color: "#3d4a44", mb: 1, fontStyle: "italic" }}>
        “{row.conditionText}”
      </Typography>

      <Stack direction="row" spacing={1.5} sx={{ mb: 1, flexWrap: "wrap", rowGap: 1.5 }}>
        <TextField
          select size="small" label="What this condition does" sx={{ minWidth: 300 }}
          value={draft.reviewedRole}
          disabled={readOnly || save.isPending}
          onChange={(event) => {
            const reviewedRole = event.target.value;
            setDraft((d) => ({
              ...d,
              reviewedRole,
              // Exclusion authority is dropped the moment the role stops being a scope gate. Leaving
              // it checked would keep a control excluded on a classification that no longer exists —
              // the server recomputes it the same way, this just keeps the form honest.
              authoriseExclusion: reviewedRole === "SCOPE_GATE" ? d.authoriseExclusion : false,
            }));
          }}
        >
          {ROLES.map((role) => (
            <MenuItem key={role.value} value={role.value}>
              <Box>
                <Typography sx={{ fontSize: 13 }}>{role.label}</Typography>
                <Typography sx={{ fontSize: 11, color: palette.muted }}>{role.hint}</Typography>
              </Box>
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <TextField
        size="small" fullWidth multiline minRows={2}
        label="Why — recorded with your name against this decision"
        value={draft.reviewerNotes}
        disabled={readOnly || save.isPending}
        error={changed && noteMissing}
        helperText={
          changed && noteMissing
            ? "Required. A role change with no rationale cannot be reviewed later."
            : " "
        }
        onChange={(event) => setDraft((d) => ({ ...d, reviewerNotes: event.target.value }))}
        sx={{ mb: 1 }}
      />

      {canOfferExclusion && (
        <Alert severity="warning" sx={{ mb: 1, py: 0.25 }}>
          <Typography sx={{ fontSize: 12.5, mb: 0.5 }}>
            <strong>Authorise this condition to exclude a requirement?</strong> Confirming what a clause
            does is not the same as authorising it to remove a control from a review, so this is a
            separate decision.
          </Typography>
          <Button
            size="small"
            color={draft.authoriseExclusion ? "error" : "inherit"}
            variant={draft.authoriseExclusion ? "contained" : "outlined"}
            disabled={readOnly || save.isPending}
            onClick={() => setDraft((d) => ({ ...d, authoriseExclusion: !d.authoriseExclusion }))}
          >
            {draft.authoriseExclusion ? "Authorised — click to withdraw" : "Authorise exclusion"}
          </Button>
          <Typography sx={{ fontSize: 11.5, color: palette.muted, mt: 0.5 }}>
            Even authorised, a requirement is excluded only when the review runs with a document type
            that clearly conflicts with the stated scope. Otherwise it is reported as an open
            applicability question, never silently dropped.
          </Typography>
        </Alert>
      )}

      {error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert>}

      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Button
          size="small" variant="contained"
          disabled={readOnly || !changed || noteMissing || save.isPending}
          onClick={() => save.mutate()}
        >
          {save.isPending ? "Saving…" : "Save decision"}
        </Button>
        {save.isSuccess && !changed && (
          <Typography sx={{ fontSize: 12, color: palette.muted }}>Saved</Typography>
        )}
      </Stack>
    </Box>
  );
}

export function ApplicabilityDialog({
  open, onClose, standardVersionId, standardTitle, versionLabel, readOnly,
}: {
  open: boolean;
  onClose: () => void;
  standardVersionId: string;
  standardTitle: string;
  versionLabel: string;
  readOnly: boolean;
}) {
  const conditions = useQuery({
    queryKey: ["applicability", standardVersionId],
    queryFn: () =>
      apiClient.get<ConditionsResponse>(
        `/admin/standards/versions/${standardVersionId}/conditions`),
    enabled: open && Boolean(standardVersionId),
  });

  const rows = conditions.data?.conditions ?? [];
  const setVersion = conditions.data?.requirementSetVersion ?? null;

  // Counted separately on purpose: the GAP between them is the safety signal. Scope gates are what
  // COULD exclude; authorised is what actually may. A single number would hide that distinction.
  const summary = useMemo(() => ({
    gates: rows.filter((r) => r.effectiveRole === "SCOPE_GATE").length,
    canExclude: rows.filter((r) => r.canDriveNotApplicable).length,
    open: rows.filter((r) => r.effectiveRole === "UNKNOWN").length,
  }), [rows]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ pb: 0.5 }}>
        <Typography sx={{ fontSize: 15, fontWeight: 600 }}>Applicability conditions</Typography>
        <Typography sx={{ fontSize: 12.5, color: palette.muted }}>
          {standardTitle} · {versionLabel}
          {setVersion && ` · ${setVersion}`}
        </Typography>
      </DialogTitle>

      <DialogContent>
        {conditions.isLoading && (
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", py: 2 }}>
            <CircularProgress size={16} />
            <Typography sx={{ fontSize: 12.5 }}>Loading conditions…</Typography>
          </Stack>
        )}

        {conditions.isError && (
          <Alert severity="error">Could not load conditions for this version.</Alert>
        )}

        {/*
          THREE DISTINCT EMPTY STATES, and conflating them misinforms the administrator.

          No requirement set  — indexing has not produced one yet, so there is nothing to review YET.
          Set but no conditions — the standard genuinely states none, or it was extracted before
                                  conditions were recorded, which looks identical here and is the more
                                  likely cause on an older corpus.
        */}
        {!conditions.isLoading && !conditions.isError && !setVersion && (
          <Alert severity="info">
            This version has no published requirement set yet. Conditions are extracted with the
            requirements, so there is nothing to review until indexing finishes.
          </Alert>
        )}

        {!conditions.isLoading && setVersion && rows.length === 0 && (
          <Alert severity="info">
            <Typography sx={{ fontSize: 12.5 }}>
              No applicability conditions were recorded for this requirement set.
            </Typography>
            <Typography sx={{ fontSize: 12, color: palette.muted, mt: 0.5 }}>
              Either the standard states none, or it was extracted before conditions were captured. An
              older set shows no conditions even when the standard clearly has them — re-indexing under
              the current extraction is what populates them.
            </Typography>
          </Alert>
        )}

        {rows.length > 0 && (
          <>
            <Stack direction="row" spacing={1} sx={{ mb: 1, flexWrap: "wrap" }}>
              <Chip size="small" variant="outlined" label={`${rows.length} conditions`} />
              <Chip size="small" variant="outlined" label={`${summary.gates} scope gates`} />
              <Chip
                size="small"
                color={summary.canExclude > 0 ? "error" : "default"}
                variant={summary.canExclude > 0 ? "filled" : "outlined"}
                label={`${summary.canExclude} may exclude a requirement`}
              />
              {summary.open > 0 && (
                <Chip size="small" color="warning" variant="outlined"
                  label={`${summary.open} open questions`} />
              )}
            </Stack>

            <Typography sx={{ ...overlineLabel, fontSize: 9, mb: 0.5 }}>
              Only a scope gate can exclude a requirement
            </Typography>
            <Typography sx={{ fontSize: 12, color: palette.muted, mb: 1 }}>
              Expect the model to over-predict scope gates. “For cloud-hosted systems” limits who the
              rule covers; “when a change is urgent” only says when it fires — treating the second as a
              scope gate would silently excuse a real requirement.
            </Typography>

            <Divider />
            {rows.map((row) => (
              <ConditionEditor
                key={`${row.requirementId}:${row.conditionText}`}
                row={row}
                standardVersionId={standardVersionId}
                readOnly={readOnly}
              />
            ))}
          </>
        )}
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
