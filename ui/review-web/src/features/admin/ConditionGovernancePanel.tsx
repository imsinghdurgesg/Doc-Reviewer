import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert, Box, Chip, CircularProgress, Divider, FormControlLabel, MenuItem, Stack, Switch,
  Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { PolicyOwnerQueue } from "@/features/admin/PolicyOwnerQueue";
import type { StandardSummary } from "@/models/comparison";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Applicability-condition governance. Architecture §12.5, §19.2.
 *
 * <strong>Why this page exists.</strong> Only a SCOPE_GATE can make a requirement NOT_APPLICABLE, and a
 * wrongly classified one silently removes a real control from a review — producing a clean-looking
 * report that is wrong. This is where that becomes auditable: which conditions a person reviewed, which
 * the extraction model still owns unreviewed, which are actually authorised to exclude a control, and
 * which are open questions waiting on a policy owner.
 *
 * <strong>Read-only, deliberately.</strong> There is no edit control. Overrides are imported as a hashed
 * review batch, so every governed decision traces to an artifact someone signed off. An inline dropdown
 * would create authoritative decisions with no reviewer, no rationale and no batch — discarding exactly
 * the provenance that makes a decision defensible six months later.
 */

/** Mirrors StandardAdminController.ConditionsResponse. */
interface ConditionsResponse {
  /**
   * Which set these came from, resolved server-side.
   *
   * Null when the version has no comparable requirement set — meaningfully different from "a set with
   * no conditions", and the two used to be indistinguishable here.
   */
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

const ROLE_LABEL: Record<string, string> = {
  SCOPE_GATE: "scope gate",
  CONDITIONAL_OBLIGATION: "conditional obligation",
  IMPLEMENTATION_QUALIFIER: "implementation qualifier",
  EXCEPTION_OR_WAIVER: "exception or waiver",
  TEMPORAL_OR_SEQUENCE: "temporal or sequence",
  UNKNOWN: "unknown",
};

const SOURCE_LABEL: Record<string, string> = {
  POLICY_OWNER_OVERRIDE: "policy owner",
  REVIEWER_OVERRIDE: "reviewer",
  STRUCTURED_METADATA: "structured metadata",
  MODEL_EXTRACTED: "model, unreviewed",
  UNRESOLVED: "awaiting policy owner",
};

function role(value: string) {
  return ROLE_LABEL[value] ?? value.replace(/_/g, " ").toLowerCase();
}

/**
 * Who decided, and how much weight it carries.
 *
 * A human decision and an unreviewed model guess must not look alike. On this corpus the model's
 * scope-gate predictions were wrong 113 times out of 115, so "model, unreviewed" is a caution, and
 * "awaiting policy owner" is an explicitly open question rather than a lesser form of decided.
 */
function SourceChip({ source }: { source: string }) {
  const governed = source === "REVIEWER_OVERRIDE" || source === "POLICY_OWNER_OVERRIDE";
  const open = source === "UNRESOLVED";
  return (
    <Chip
      size="small"
      variant={governed ? "filled" : "outlined"}
      color={open ? "warning" : "default"}
      label={SOURCE_LABEL[source] ?? source.replace(/_/g, " ").toLowerCase()}
    />
  );
}

export function ConditionGovernancePanel() {
  const [versionId, setVersionId] = useState("");
  const [reclassifiedOnly, setReclassifiedOnly] = useState(false);

  const standards = useQuery({
    queryKey: ["admin", "standards", "for-conditions"],
    queryFn: () => apiClient.get<readonly StandardSummary[]>("/admin/standards"),
  });

  /**
   * Every version, flattened.
   *
   * NOT filtered on `requirementSetVersion`. That field is review-core's denormalised copy and goes
   * stale — it read REQSET-V1 for every version long after V3 was live, so filtering on it both hid
   * versions and, worse, previously supplied the wrong set to query. Which set is current is resolved
   * server-side by the service that owns it.
   */
  const versions = useMemo(() => {
    const rows: Array<{ id: string; label: string }> = [];
    (standards.data ?? []).forEach((standard) => {
      standard.versions.forEach((version) => {
        rows.push({ id: version.id, label: `${standard.title} — v${version.version}` });
      });
    });
    return rows;
  }, [standards.data]);

  const conditions = useQuery({
    queryKey: ["admin", "conditions", versionId],
    queryFn: () =>
      apiClient.get<ConditionsResponse>(`/admin/standards/versions/${versionId}/conditions`),
    enabled: Boolean(versionId),
  });

  const setVersion = conditions.data?.requirementSetVersion ?? null;
  const allRows = conditions.data?.conditions ?? [];

  const rows = useMemo(
    () => (reclassifiedOnly ? allRows.filter((r) => r.reclassified) : allRows),
    [allRows, reclassifiedOnly],
  );

  /**
   * The numbers that matter, computed from what is loaded.
   *
   * `canExclude` is counted separately from `gates` on purpose: the gap between them is the whole
   * safety margin. A confirmed scope gate that nobody authorised still cannot remove a control.
   */
  const stats = useMemo(() => {
    const all = allRows;
    return {
      total: all.length,
      reclassified: all.filter((r) => r.reclassified).length,
      gates: all.filter((r) => r.effectiveRole === "SCOPE_GATE").length,
      canExclude: all.filter((r) => r.canDriveNotApplicable).length,
      unresolved: all.filter((r) => r.source === "UNRESOLVED").length,
      unreviewed: all.filter((r) => r.source === "MODEL_EXTRACTED").length,
    };
  }, [allRows]);

  return (
    <Stack spacing={4}>
      {/* FIRST, before the per-standard table. An unresolved question is org-wide and does not belong
          behind a version picker — someone would have to guess which standard to look under. */}
      <PolicyOwnerQueue />

      <Divider />

      <Stack spacing={2.5}>
      <Box>
        <Typography sx={overlineLabel}>Applicability conditions</Typography>
        <Typography sx={{ fontSize: 13, color: "#5c6a63", mt: 0.5, maxWidth: 780 }}>
          Only a scope gate can mark a requirement as not applicable, and only when a review has
          explicitly authorised it. Everything else informs the comparison instead, so the requirement is
          still measured.
        </Typography>
      </Box>

      <Stack direction="row" spacing={1.5} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
        <TextField
          select
          size="small"
          label="Standard version"
          value={versionId}
          onChange={(event) => setVersionId(event.target.value)}
          sx={{ minWidth: 320 }}
          disabled={standards.isLoading}
        >
          {versions.map((version) => (
            <MenuItem key={version.id} value={version.id}>
              {version.label}
            </MenuItem>
          ))}
        </TextField>

        {/* States WHICH set is on screen. Without it, an empty table is ambiguous between "this
            standard states no conditions" and "we queried the wrong set" — and that ambiguity is
            exactly what made a stale-metadata bug look like a clean review. */}
        {setVersion && (
          <Chip size="small" variant="outlined"
            label={conditions.data?.promptVersion
              ? `${setVersion} · ${conditions.data.promptVersion}`
              : setVersion} />
        )}

        <FormControlLabel
          control={
            <Switch
              size="small"
              checked={reclassifiedOnly}
              onChange={(event) => setReclassifiedOnly(event.target.checked)}
            />
          }
          label={<Typography sx={{ fontSize: 13 }}>Changed by review only</Typography>}
        />
      </Stack>

      {!versionId && (
        <Alert severity="info">Choose a standard version to inspect its conditions.</Alert>
      )}

      {conditions.isLoading && (
        <Stack sx={{ alignItems: "center", py: 4 }}>
          <CircularProgress size={24} />
        </Stack>
      )}

      {/* Three distinct empty states, because they need three different actions. */}
      {versionId && !conditions.isLoading && !setVersion && (
        <Alert severity="warning">
          This version has no comparable requirement set — extraction has not run, or produced a set
          that still needs review. There is nothing to govern yet.
        </Alert>
      )}

      {versionId && !conditions.isLoading && setVersion && stats.total === 0 && (
        <Alert severity="info">
          {setVersion} states no applicability conditions. Every requirement in it applies
          unconditionally.
        </Alert>
      )}

      {stats.total > 0 && (
        <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap" }}>
          <Chip size="small" variant="outlined" label={`${stats.total} conditions`} />
          <Chip size="small" variant="outlined" label={`${stats.reclassified} changed by review`} />
          <Chip size="small" variant="outlined" label={`${stats.gates} scope gates`} />
          {/*
            The load-bearing number. If this is 0, no condition in this set can remove a control from a
            review — which is the safe state, and worth stating rather than leaving to be inferred.
          */}
          <Tooltip title="Conditions authorised to mark a requirement not applicable">
            <Chip
              size="small"
              color={stats.canExclude > 0 ? "warning" : "default"}
              variant={stats.canExclude > 0 ? "filled" : "outlined"}
              label={`${stats.canExclude} can exclude`}
            />
          </Tooltip>
          {stats.unresolved > 0 && (
            <Chip size="small" color="warning" variant="outlined"
              label={`${stats.unresolved} awaiting policy owner`} />
          )}
          {stats.unreviewed > 0 && (
            <Chip size="small" variant="outlined" label={`${stats.unreviewed} unreviewed`} />
          )}
        </Stack>
      )}

      {rows.length > 0 && (
        <Box sx={{ border: `1px solid ${palette.border}`, borderRadius: 1, overflowX: "auto" }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Requirement</TableCell>
                <TableCell>Condition</TableCell>
                {/* Both roles, side by side. Showing only the effective one would hide the
                    disagreement — and the disagreement is the only measure of extraction quality. */}
                <TableCell>Extracted</TableCell>
                <TableCell>Governs</TableCell>
                <TableCell>Decided by</TableCell>
                <TableCell>Can exclude</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row, index) => (
                <TableRow key={`${row.requirementId}-${index}`} hover>
                  <TableCell sx={{ whiteSpace: "nowrap", fontSize: 12 }}>
                    {row.requirementId}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12.5, minWidth: 220 }}>
                    {/* Verbatim from the standard: the reviewer's evidence, not a paraphrase. */}
                    {row.conditionText || <em style={{ color: "#8a958f" }}>no text extracted</em>}
                    {row.reviewerNotes && (
                      <Typography sx={{ fontSize: 11.5, color: "#5c6a63", mt: 0.5 }}>
                        {row.reviewerNotes}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, whiteSpace: "nowrap" }}>
                    <Typography
                      component="span"
                      sx={{
                        fontSize: 12,
                        // Struck through when review overruled it, so a scan down the column shows
                        // where the extractor was wrong without reading both cells.
                        textDecoration: row.reclassified ? "line-through" : "none",
                        color: row.reclassified ? "#8a958f" : "inherit",
                      }}
                    >
                      {role(row.extractedRole)}
                    </Typography>
                  </TableCell>
                  <TableCell sx={{ fontSize: 12, whiteSpace: "nowrap", fontWeight: 600 }}>
                    {role(row.effectiveRole)}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
                      <SourceChip source={row.source} />
                      {row.overrideBatchId && (
                        <Tooltip title={`Review batch ${row.overrideBatchId}`}>
                          <Chip size="small" variant="outlined" label="batch" />
                        </Tooltip>
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}>
                    {row.canDriveNotApplicable ? (
                      <Chip size="small" color="warning" label="yes" />
                    ) : (
                      <Typography sx={{ fontSize: 12, color: "#8a958f" }}>no</Typography>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}

      {stats.total > 0 && rows.length === 0 && (
        <Alert severity="success">
          Review agreed with the extraction on every condition in this set.
        </Alert>
      )}
      </Stack>
    </Stack>
  );
}
