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
 *
 * <strong>Wording is deliberately non-technical.</strong> The domain terms — scope gate, conditional
 * obligation, exclusion authority — are precise and mean nothing to a reader who has not been told what
 * they mean. Every label here asks a question in ordinary words instead, and the one control that can
 * remove a requirement from a review states its effect before the button rather than after it. The
 * stored values are unchanged; only what a person reads is.
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
 * The roles, phrased as what a reader would say rather than what the system calls them.
 *
 * Only SCOPE_GATE can lead to a skipped requirement, and the model over-predicts it heavily — in one
 * reviewed corpus it proposed 117 and review confirmed none. So each option leads with the question it
 * answers, and every one carries a worked example: the WHO-versus-WHEN distinction is the judgement the
 * whole screen turns on, and an abstract label does not help anyone make it.
 */
const ROLES: ReadonlyArray<{ value: string; label: string; hint: string }> = [
  {
    value: "SCOPE_GATE",
    label: "It limits WHO the rule applies to",
    hint: "e.g. \"for cloud-hosted systems\" — the rule does not cover other kinds of document",
  },
  {
    value: "CONDITIONAL_OBLIGATION",
    label: "It says WHEN the rule kicks in",
    hint: "e.g. \"when a change is urgent\" — the rule still applies here, it just fires sometimes",
  },
  {
    value: "IMPLEMENTATION_QUALIFIER",
    label: "It says HOW to meet the rule",
    hint: "e.g. \"using the approved template\" — detail about compliance, not about who is covered",
  },
  {
    value: "EXCEPTION_OR_WAIVER",
    label: "It is an exception to the rule",
    hint: "e.g. \"unless the CISO has granted a waiver\" — a carve-out from a rule that does apply",
  },
  {
    value: "TEMPORAL_OR_SEQUENCE",
    label: "It is a deadline or an order of steps",
    hint: "e.g. \"within two business days\", \"before deployment\"",
  },
  {
    value: "UNKNOWN",
    label: "I am not sure — pass it to someone who decides policy",
    hint: "Records it as an open question instead of guessing. Nothing gets skipped",
  },
];

/**
 * Short labels for the chips, where the sentence-style options above would not fit.
 *
 * Kept separate from the dropdown text rather than reused: a chip has room for a name and a dropdown
 * option has room for an explanation, and forcing one string to serve both made the dropdown
 * unreadable to anyone who did not already know the vocabulary.
 */
const ROLE_CHIP: Record<string, string> = {
  SCOPE_GATE: "limits who it applies to",
  CONDITIONAL_OBLIGATION: "says when it kicks in",
  IMPLEMENTATION_QUALIFIER: "says how to comply",
  EXCEPTION_OR_WAIVER: "an exception",
  TEMPORAL_OR_SEQUENCE: "a deadline or ordering",
  UNKNOWN: "undecided",
};



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
          label={`the tool guessed: ${ROLE_CHIP[row.extractedRole] ?? row.extractedRole}`} />
        {row.reclassified && (
          <Chip size="small" color="warning" variant="outlined" label="a person changed this" />
        )}
        {row.canDriveNotApplicable && (
          <Chip size="small" color="error" label="can skip this requirement" />
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
          select size="small" label="What does this condition do?" sx={{ minWidth: 300 }}
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
        label="Why did you decide that? Saved with your name"
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

      {/*
        PLAIN LANGUAGE, deliberately. This is the only control in the product that can make a
        requirement vanish from a review, so the person clicking it has to understand the consequence
        without knowing the vocabulary. "Authorise this condition to exclude a requirement" described
        the mechanism correctly and told a reader nothing about what would happen to their reviews.

        Phrased as the question it actually asks — should we stop checking this? — with the effect
        stated before the button rather than after it.
      */}
      {canOfferExclusion && (
        <Alert severity="warning" sx={{ mb: 1, py: 0.25 }}>
          <Typography sx={{ fontSize: 12.5, mb: 0.5 }}>
            <strong>Should we stop checking this requirement for documents outside that scope?</strong>
          </Typography>
          <Typography sx={{ fontSize: 12, color: "#3d4a44", mb: 0.75 }}>
            You said this condition limits which documents the rule covers. Saying yes here lets the
            tool skip this requirement entirely when a document falls outside it — the requirement
            will not appear in those reviews at all.
          </Typography>
          <Button
            size="small"
            color={draft.authoriseExclusion ? "error" : "inherit"}
            variant={draft.authoriseExclusion ? "contained" : "outlined"}
            disabled={readOnly || save.isPending}
            onClick={() => setDraft((d) => ({ ...d, authoriseExclusion: !d.authoriseExclusion }))}
          >
            {draft.authoriseExclusion
              ? "Yes — skipping is allowed. Click to undo"
              : "Yes, allow skipping"}
          </Button>
          <Typography sx={{ fontSize: 11.5, color: palette.muted, mt: 0.75 }}>
            Leave this alone if you are unsure. The requirement then stays in every review and this
            condition simply appears as context — which is the safer outcome, because a requirement you
            review and dismiss yourself costs a minute, and one the tool skipped by mistake is a gap
            nobody sees.
          </Typography>
          <Typography sx={{ fontSize: 11.5, color: palette.muted, mt: 0.5 }}>
            Even with this on, a requirement is only skipped when the reviewer set a document type that
            clearly falls outside the stated scope. Otherwise the review flags it as an open question
            for a person, rather than dropping it quietly.
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
              <Chip size="small" variant="outlined" label={`${summary.gates} limit who the rule applies to`} />
              <Chip
                size="small"
                color={summary.canExclude > 0 ? "error" : "default"}
                variant={summary.canExclude > 0 ? "filled" : "outlined"}
                label={`${summary.canExclude} allowed to skip a requirement`}
              />
              {summary.open > 0 && (
                <Chip size="small" color="warning" variant="outlined"
                  label={`${summary.open} awaiting a decision`} />
              )}
            </Stack>

            <Typography sx={{ ...overlineLabel, fontSize: 9, mb: 0.5 }}>
              The one distinction that matters
            </Typography>
            <Typography sx={{ fontSize: 12, color: palette.muted, mb: 1 }}>
              “For cloud-hosted systems” limits <strong>who</strong> the rule applies to. “When a change
              is urgent” only says <strong>when</strong> it kicks in — the rule still applies. The tool
              often confuses the two, and marking the second as a limit would quietly excuse a real
              requirement. Correcting that is most of the work here.
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
