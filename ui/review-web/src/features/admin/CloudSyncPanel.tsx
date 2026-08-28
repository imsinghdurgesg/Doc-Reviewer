import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Divider,
  FormControlLabel,
  InputAdornment,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import CloudSyncIcon from "@mui/icons-material/CloudSync";
import NetworkCheckIcon from "@mui/icons-material/NetworkCheck";
import { apiClient } from "@/api/client";
import { useCurrentUser } from "@/auth/AuthGate";
import { canAdminister } from "@/auth/session";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Google Cloud Storage sync configuration. Architecture §41.
 *
 * <p>A configuration surface, always available. An earlier version rendered only a message telling
 * the reader to edit an environment file — which is a dead end, because this screen is where the
 * bucket is meant to be configured in the first place.
 *
 * <p>Saving and testing work in every deployment. Running a PASS additionally needs the GCS storage
 * provider, so `syncAvailable` gates the trigger and the reason is stated rather than surfacing as a
 * failed click.
 */

interface SkippedObject {
  objectName: string;
  reason: string;
}

interface SyncResult {
  discovered: number;
  ingested: number;
  unchanged: number;
  skipped: SkippedObject[];
  truncated: boolean;
  startedAt: string;
  completedAt: string;
}

interface CloudSyncSettings {
  enabled: boolean;
  /** False when the deployment is not on the GCS provider; settings are still editable. */
  syncAvailable: boolean;
  projectId: string | null;
  bucket: string | null;
  standardsPrefix: string | null;
  incomingPrefix: string | null;
  archiveUploads: boolean;
  lastTestOk: boolean | null;
  lastTestMessage: string | null;
  lastTestedAt: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  lastResult: SyncResult | null;
}

export function CloudSyncPanel() {
  // Disabled, not hidden: see StandardsAdminPage. The backend rejects the write regardless, so
  // rendering the form read-only tells a reviewer what exists without granting anything.
  const { user } = useCurrentUser();
  const readOnly = !canAdminister(user);
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ["cloud-sync"],
    queryFn: () => apiClient.get<CloudSyncSettings>("/admin/standards/sync"),
  });

  const [form, setForm] = useState({
    enabled: false,
    projectId: "",
    bucket: "",
    standardsPrefix: "standards/",
    incomingPrefix: "incoming/",
    archiveUploads: false,
  });

  useEffect(() => {
    const data = settings.data;
    if (!data) {
      return;
    }
    setForm({
      enabled: data.enabled,
      projectId: data.projectId ?? "",
      bucket: data.bucket ?? "",
      standardsPrefix: data.standardsPrefix ?? "standards/",
      incomingPrefix: data.incomingPrefix ?? "incoming/",
      archiveUploads: data.archiveUploads,
    });
  }, [settings.data]);

  const save = useMutation({
    mutationFn: () => apiClient.put<CloudSyncSettings>("/admin/standards/sync", form),
    onSuccess: (data) => queryClient.setQueryData(["cloud-sync"], data),
  });

  // Saves FIRST, then tests. Testing the stored settings while the form holds unsaved edits would
  // report on a bucket the administrator is no longer looking at — the label says "Save & test"
  // because that is what has to happen for the result to mean anything.
  const testBucket = useMutation({
    mutationFn: async () => {
      await apiClient.put<CloudSyncSettings>("/admin/standards/sync", form);
      return apiClient.post<CloudSyncSettings>("/admin/standards/sync/test", {});
    },
    onSuccess: (data) => queryClient.setQueryData(["cloud-sync"], data),
  });

  const syncNow = useMutation({
    mutationFn: () => apiClient.post<SyncResult>("/admin/standards/sync", {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["cloud-sync"] }),
  });

  if (settings.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", p: 4 }}>
        {readOnly && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Cloud sync settings are read-only for your role. Changing the bucket or triggering a sync requires the Admin role.
          </Alert>
        )}

        <CircularProgress />
      </Stack>
    );
  }

  const data = settings.data;
  const result = settings.data?.lastResult ?? null;
  const update = (patch: Partial<typeof form>) => setForm((c) => ({ ...c, ...patch }));

  return (
    <Box sx={{ bgcolor: "#fff", border: 1, borderColor: "divider", borderRadius: 1 }}>
      <Stack
        direction="row"
        sx={{ p: 3, borderBottom: 1, borderColor: "divider", alignItems: "center" }}
      >
        <Box sx={{ flex: 1 }}>
          <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.4px" }}>
            Google Cloud Storage sync
          </Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mt: 0.5 }}>
            Mirror a bucket prefix into the standards library: objects are parsed, indexed, and
            published as standard versions.
          </Typography>
        </Box>
        <FormControlLabel
          control={
            <Switch
              checked={form.enabled}
              onChange={(event) => update({ enabled: event.target.checked })}
            />
          }
          label={form.enabled ? "Enabled" : "Disabled"}
          sx={{ mr: 0 }}
        />
      </Stack>

      <Stack spacing={3} sx={{ p: 3 }}>
        {!data?.syncAvailable && (
          // Settings are still saveable — verifying a bucket before switching the deployment over is
          // exactly when this screen is useful.
          <Alert severity="info">
            This deployment stores documents locally, so a sync pass cannot run yet. Settings below
            can still be saved and tested; set <code>STORAGE_PROVIDER=gcs</code> and restart to
            enable syncing.
          </Alert>
        )}

        {data?.lastTestMessage && (
          <Alert severity={data.lastTestOk ? "success" : "error"}>
            {data.lastTestMessage}
            {data.lastTestedAt && (
              <Typography component="span" variant="caption" sx={{ ml: 1, color: palette.muted }}>
                ({new Date(data.lastTestedAt).toLocaleString()})
              </Typography>
            )}
          </Alert>
        )}

        {save.isError && (
          <Alert severity="error">
            {save.error instanceof Error ? save.error.message : "Could not save."}
          </Alert>
        )}
        {syncNow.isError && (
          <Alert severity="error">
            {syncNow.error instanceof Error ? syncNow.error.message : "Sync failed."}
          </Alert>
        )}

        <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", rowGap: 2 }}>
          <TextField
            label="GCP project ID" size="small" sx={{ flex: 1, minWidth: 260 }}
            value={form.projectId}
            onChange={(event) => update({ projectId: event.target.value })}
            placeholder="my-gcp-project"
            helperText="Optional — Application Default Credentials usually supply it."
          />
          <TextField
            label="Bucket name" size="small" sx={{ flex: 1, minWidth: 260 }}
            value={form.bucket}
            onChange={(event) => update({ bucket: event.target.value })}
            placeholder="stanford-docreview-standards"
            slotProps={{
              input: {
                startAdornment: <InputAdornment position="start">gs://</InputAdornment>,
              },
            }}
          />
        </Stack>

        <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", rowGap: 2 }}>
          <TextField
            label="Standards prefix" size="small" sx={{ flex: 1, minWidth: 260 }}
            value={form.standardsPrefix}
            onChange={(event) => update({ standardsPrefix: event.target.value })}
            helperText="Objects are read as <prefix>/<standard-key>/<version>/<filename>."
          />
          <TextField
            label="Incoming prefix" size="small" sx={{ flex: 1, minWidth: 260 }}
            value={form.incomingPrefix}
            onChange={(event) => update({ incomingPrefix: event.target.value })}
            // Stated plainly rather than left looking functional. The value is stored but nothing
            // reads it, so an administrator who sets it and expects uploads to be archived would be
            // wrong — with no way to discover that from this screen.
            helperText="Archiving uploads is not yet implemented — this is stored but has no effect."
          />
        </Stack>

        <Box>
          <Typography sx={overlineLabel}>Authentication</Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mt: 0.5 }}>
            Application Default Credentials from the server environment — a workload identity or
            attached service account in deployment. There is deliberately no field for
            service-account JSON: a downloaded private key is the credential most likely to end up in
            a ticket, a log, or an image layer.
          </Typography>
        </Box>

        <Divider />

        <FormControlLabel
          control={
            <Checkbox
              checked={form.archiveUploads}
              onChange={(event) => update({ archiveUploads: event.target.checked })}
            />
          }
          label={
            <Box>
              <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                Archive comparison uploads to the incoming prefix
              </Typography>
              <Typography sx={{ fontSize: 12, color: palette.muted }}>
                Off by default: a current-process document is the customer&apos;s own material and may
                be confidential, so copying it to cloud storage is a data-policy decision.
              </Typography>
            </Box>
          }
        />

        {result && (
          <>
            <Divider />
            <Box>
              <Typography sx={{ ...overlineLabel, mb: 1.5 }}>Last sync pass</Typography>
              <Stack direction="row" sx={{ flexWrap: "wrap" }}>
                <Stat label="Discovered" value={result.discovered} />
                <Stat label="Ingested" value={result.ingested} highlight />
                <Stat label="Unchanged" value={result.unchanged} />
                <Stat label="Skipped" value={result.skipped.length} />
              </Stack>
              <Typography variant="caption" sx={{ color: palette.muted }}>
                Completed {new Date(result.completedAt).toLocaleString()}
              </Typography>

              {result.truncated && (
                // Reported rather than inferred: a silently capped pass reads as a complete one.
                <Alert severity="warning" sx={{ mt: 2 }}>
                  The bucket holds more objects than one pass examines. The next pass continues from
                  what is still missing.
                </Alert>
              )}

              {result.skipped.length > 0 && (
                <Table size="small" sx={{ mt: 2 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell sx={overlineLabel}>Object</TableCell>
                      <TableCell sx={overlineLabel}>Reason</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {result.skipped.map((item) => (
                      <TableRow key={item.objectName}>
                        <TableCell sx={{ fontSize: 12, wordBreak: "break-all" }}>
                          {item.objectName}
                        </TableCell>
                        <TableCell sx={{ fontSize: 12, color: palette.muted }}>
                          {item.reason}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Box>
          </>
        )}
      </Stack>

      <Stack
        direction="row"
        spacing={2}
        sx={{ p: 3, pt: 0, justifyContent: "flex-end", alignItems: "center", flexWrap: "wrap",
              rowGap: 1 }}
      >
        {data?.updatedAt && (
          <Typography variant="caption" sx={{ color: palette.muted, mr: "auto" }}>
            Last saved {new Date(data.updatedAt).toLocaleString()}
            {data.updatedBy ? ` by ${data.updatedBy}` : ""}
          </Typography>
        )}
        <Button
          startIcon={<NetworkCheckIcon />}
          onClick={() => testBucket.mutate()}
          disabled={readOnly || testBucket.isPending || !form.bucket.trim()}
        >
          {testBucket.isPending ? "Testing…" : "Save & test bucket"}
        </Button>
        <Button
          variant="contained"
          onClick={() => save.mutate()}
          disabled={readOnly || save.isPending}
          sx={{ minHeight: 40, px: 2.5 }}
        >
          {save.isPending ? "Saving…" : "Save"}
        </Button>
        <Button
          variant="contained"
          startIcon={<CloudSyncIcon />}
          onClick={() => syncNow.mutate()}
          // Needs the GCS provider AND a bucket. Disabled rather than hidden, so the capability is
          // discoverable before it is usable.
          disabled={readOnly || syncNow.isPending || !data?.syncAvailable
            || !form.bucket.trim()}
          sx={{ minHeight: 40, px: 2.5 }}
        >
          {syncNow.isPending ? "Syncing…" : "Pull bucket & index"}
        </Button>
      </Stack>
    </Box>
  );
}

function Stat({ label, value, highlight }: { label: string; value: number; highlight?: boolean }) {
  return (
    <Box
      sx={{
        minWidth: 110,
        px: 2.5,
        py: 1,
        borderLeft: 1,
        borderColor: "divider",
        "&:first-of-type": { borderLeft: 0, pl: 0 },
      }}
    >
      <Typography sx={overlineLabel}>{label}</Typography>
      <Typography
        sx={{ fontSize: 20, fontWeight: 700, color: highlight ? palette.forest : "text.primary" }}
      >
        {value}
      </Typography>
    </Box>
  );
}
