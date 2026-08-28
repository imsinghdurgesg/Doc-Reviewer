import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  InputAdornment,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import SearchIcon from "@mui/icons-material/Search";
import { ApiError, apiClient } from "@/api/client";
import { useCurrentUser } from "@/auth/AuthGate";
import { canAdminister } from "@/auth/session";
import { summariseIssues } from "@/features/standards/extractionIssues";
import type { StandardSummary, StandardVersionSummary } from "@/models/comparison";
import { toQuery, type PageResponse } from "@/models/page";
import { PageControls } from "@/components/pagination/PageControls";
import { useDebounced } from "@/hooks/useDebounced";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Standards repository administration. Architecture §8.1, §20.1, §33.
 *
 * Two-step by necessity, mirroring the domain: a STANDARD is the durable identity (key, title,
 * owner) and a VERSION is immutable content. Collapsing them into one form would imply a standard
 * and its document are the same thing, and the whole point of §19.2 is that a standard outlives any
 * single version of its text.
 *
 * Indexing is slow and asynchronous (§20.1: parse, then one LLM extraction call per candidate
 * group), so this page polls and shows the pipeline STAGE rather than a spinner. A reviewer who
 * cannot tell "extracting requirements" from "stuck" will refresh, re-upload, and duplicate work.
 *
 * Activation is deliberately a separate, explicit action. §8.1 refuses to activate a version that is
 * not INDEXED, because an ACTIVE standard with no extracted requirements produces comparisons with
 * no findings — which reads as full compliance and is the worst way this system can be wrong.
 */
/**
 * @param embedded true when rendered inside the Admin tab shell, which already supplies the page
 *     heading and outer padding. Without this the two stack, producing a heading under a heading and
 *     a doubly-indented panel.
 */
export function StandardsAdminPage({ embedded = false }: { embedded?: boolean } = {}) {
  // Controls are DISABLED for a reviewer, never hidden. Hiding the screen leaves someone unable to
  // discover that a setting exists or that they need to ask for access; a disabled field with a
  // stated reason answers both. The backend enforces this regardless — see canAdminister.
  const { user } = useCurrentUser();
  const readOnly = !canAdminister(user);
  const queryClient = useQueryClient();

  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({
    standardKey: "", title: "", owner: "", description: "",
    category: "", businessUnit: "", tags: "",
  });
  /**
   * The standard whose curated metadata is being edited, plus the form values.
   *
   * <p>These three labels are RANKING signals for standard selection (§15.1), and MongoDB is their
   * source of truth — the vector payload is rebuilt from it on save. An unlabelled corpus makes the
   * whole ranking path inert, so this form is how the signals get populated at all.
   */
  const [metadataTarget, setMetadataTarget] = useState<StandardSummary | null>(null);
  const [metadataForm, setMetadataForm] = useState({
    businessUnit: "", category: "", standardFamily: "", documentType: "", tags: "",
  });
  const [uploadTarget, setUploadTarget] = useState<{ standardId: string; version: string } | null>(
    null,
  );
  // File is held until the user confirms, so the version label can still be corrected.
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const [selectedVersion, setSelectedVersion] = useState("");

  // Server-side search, sort and pagination. Filtering in the browser needed the whole library
  // first, and every standard carries its full version history in the response.
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("TITLE");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  // The field updates per keystroke; the query waits for typing to stop.
  const debouncedSearch = useDebounced(search, 300);

  const standards = useQuery({
    queryKey: ["standards", debouncedSearch, sort, page, size],
    queryFn: () =>
      apiClient.get<PageResponse<StandardSummary>>(
        `/admin/standards/search${toQuery({ search: debouncedSearch, sort, page, size })}`,
      ),
    // Keeps the previous page on screen while the next loads, so the list does not collapse to a
    // spinner on every keystroke.
    placeholderData: (previous) => previous,
    // Overrides the client's 60s default: a filtered list is a question about current state, and
    // caching it makes a repeated filter selection issue no request at all.
    staleTime: 0,
    // Poll while anything is mid-pipeline. Stops once every version reaches a terminal state, so an
    // idle page is not issuing a request every three seconds forever.
    refetchInterval: (query) =>
      (query.state.data?.content ?? []).some((s) =>
        s.versions.some((v) => inProgress(v.indexingState)))
        ? 3000
        : false,
  });

  const createStandard = useMutation({
    mutationFn: () => apiClient.post<StandardSummary>("/admin/standards", {
      ...form,
      // The field is comma-separated for typing; the API takes a list. Split here rather than
      // server-side so the contract stays a list and the convenience stays in the UI.
      tags: form.tags.split(",").map((t) => t.trim()).filter((t) => t.length > 0),
    }),
    onSuccess: () => {
      setCreateOpen(false);
      setForm({
        standardKey: "", title: "", owner: "", description: "",
        category: "", businessUnit: "", tags: "",
      });
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["standards"] });
    },
    onError: (e) => setError(messageOf(e)),
  });

  /**
   * Saves curated metadata. Architecture §15.1.
   *
   * <p>PATCH rather than PUT: the endpoint leaves absent fields unchanged, so a partial form can never
   * wipe a label the form did not display. Saving also re-projects the values into every indexed
   * version's vector payload, which is why this can take a moment on a standard with several versions.
   */
  const saveMetadata = useMutation({
    mutationFn: (standardId: string) =>
      apiClient.patch<StandardSummary>(`/admin/standards/${standardId}/metadata`, {
        businessUnit: metadataForm.businessUnit,
        category: metadataForm.category,
        standardFamily: metadataForm.standardFamily,
        documentType: metadataForm.documentType,
        tags: metadataForm.tags.split(",").map((t) => t.trim()).filter((t) => t.length > 0),
      }),
    onSuccess: () => {
      setMetadataTarget(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["standards"] });
    },
    onError: (e) => setError(messageOf(e)),
  });

  const uploadVersion = useMutation({
    mutationFn: ({ standardId, version, file }: { standardId: string; version: string; file: File }) => {
      const body = new FormData();
      body.append("version", version);
      body.append("file", file);
      return apiClient.upload(`/admin/standards/${standardId}/versions`, body);
    },
    onSuccess: () => {
      setUploadTarget(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["standards"] });
    },
    onError: (e) => setError(messageOf(e)),
  });

  const activate = useMutation({
    mutationFn: (versionId: string) =>
      apiClient.patch(`/admin/standards/versions/${versionId}/lifecycle`, {
        lifecycle: "ACTIVE",
      }),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ["standards"] });
    },
    onError: (e) => setError(messageOf(e)),
  });

  const result = standards.data;
  const rows = result?.content ?? [];

  return (
    <Box sx={{ maxWidth: embedded ? "100%" : 1000, mx: "auto", p: embedded ? 0 : 3 }}>
      <Stack direction="row" sx={{ alignItems: "center", mb: 2 }}>
        <Box sx={{ flex: 1 }}>
          {/* Suppressed when embedded: the Admin shell already states where the reader is, and a
              second heading under the first reads as a nesting error. */}
          {!embedded && (
            <>
              <Typography sx={{ fontSize: 27, fontWeight: 700, letterSpacing: "-0.7px" }}>
                Standards
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Upload the authoritative documents that uploaded processes are measured against.
              </Typography>
            </>
          )}
        </Box>
        <Button variant="contained" disabled={readOnly} onClick={() => setCreateOpen(true)}>
          New standard
        </Button>
      </Stack>

      {/* States the reason, and names the role to ask for. A disabled control with no explanation is
          indistinguishable from a broken one — which is the failure this replaces, where a reviewer
          could fill in a form and only discover on Save that they were never permitted. */}
      {readOnly && (
        <Alert severity="info" sx={{ mb: 3 }}>
          You have read-only access here. Viewing standards and their versions is available to every
          reviewer; adding, uploading, and activating require the Standards Admin role.
        </Alert>
      )}

      <Stack
        direction="row"
        spacing={2}
        sx={{ mb: 3, alignItems: "center", flexWrap: "wrap", rowGap: 2 }}
      >
        <TextField
          size="small"
          sx={{ flex: 1, minWidth: 260 }}
          placeholder="Search title, key, or owner"
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
            // Back to the first page: staying on page 3 of a narrowed result shows an empty list
            // that reads as "no matches".
            setPage(0);
          }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" sx={{ color: palette.muted }} />
                </InputAdornment>
              ),
            },
          }}
        />
        <TextField
          select size="small" label="Sort by" sx={{ minWidth: 180 }}
          value={sort}
          onChange={(event) => {
            setSort(event.target.value);
            setPage(0);
          }}
        >
          <MenuItem value="TITLE">Title (A–Z)</MenuItem>
          <MenuItem value="KEY">Standard key</MenuItem>
          <MenuItem value="NEWEST">Recently updated</MenuItem>
          <MenuItem value="OLDEST">Least recently updated</MenuItem>
        </TextField>
        {result && (
          <Typography sx={{ ...overlineLabel, whiteSpace: "nowrap" }}>
            {result.totalElements} standards
          </Typography>
        )}
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {standards.isLoading && <CircularProgress size={22} />}

      {!standards.isLoading && rows.length === 0 && debouncedSearch.trim() !== "" && (
        <Alert severity="info">No standards match “{debouncedSearch.trim()}”.</Alert>
      )}

      {!standards.isLoading && rows.length === 0 && debouncedSearch.trim() === "" && (
        <Alert severity="info">
          No standards yet. Create one, then upload a version of its document — a standard becomes
          usable for reviews once its requirements have been extracted and indexed.
        </Alert>
      )}

      <Stack spacing={2}>
        {rows.map((standard) => (
          <Paper key={standard.id} variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" spacing={2} sx={{ alignItems: "flex-start" }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                  <Chip size="small" variant="outlined" label={standard.standardKey} />
                  <Typography variant="subtitle1">{standard.title}</Typography>
                </Stack>
                {standard.owner && (
                  <Typography variant="caption" sx={{ color: "text.secondary" }}>
                    Owner: {standard.owner}
                  </Typography>
                )}
                {/* Category and tags decide which standard an uploaded document is matched to
                    (§15.1), and they may have been DERIVED from the document rather than typed.
                    Shown because an inferred value nobody can see is one nobody can tell is wrong —
                    and a mis-categorised standard is one that stops being found. */}
                {/* The three RANKING dimensions are shown even when unset, as a dashed "not set"
                    chip. An absent label is invisible otherwise, and invisible is exactly why this
                    corpus went unlabelled — which leaves the whole ranking path inert (§15.1). */}
                <Stack
                  direction="row"
                  spacing={0.5}
                  sx={{ mt: 0.75, flexWrap: "wrap", rowGap: 0.5, alignItems: "center" }}
                >
                  {(
                    [
                      ["Dept", standard.businessUnit],
                      ["Family", standard.standardFamily],
                      ["Type", standard.documentType],
                    ] as const
                  ).map(([label, value]) => (
                    <Chip
                      key={label}
                      size="small"
                      variant={value ? "filled" : "outlined"}
                      label={value ? `${label}: ${value}` : `${label}: not set`}
                      sx={{
                        height: 20,
                        fontSize: 11,
                        ...(value
                          ? {}
                          : { borderStyle: "dashed", color: palette.muted }),
                      }}
                    />
                  ))}
                </Stack>
                {(standard.category || (standard.tags ?? []).length > 0) && (
                  <Stack
                    direction="row"
                    spacing={0.5}
                    sx={{ mt: 0.75, flexWrap: "wrap", rowGap: 0.5, alignItems: "center" }}
                  >
                    {standard.category && (
                      <Chip
                        size="small"
                        label={standard.category}
                        sx={{ height: 20, fontSize: 11, fontWeight: 600 }}
                      />
                    )}
                    {(standard.tags ?? []).map((tag) => (
                      <Chip
                        key={tag}
                        size="small"
                        variant="outlined"
                        label={tag}
                        sx={{ height: 20, fontSize: 11, color: palette.muted }}
                      />
                    ))}
                  </Stack>
                )}
              </Box>
              <Button
                size="small"
                onClick={() => {
                  // Prefilled from the stored values so an edit is a correction, not a re-entry —
                  // and so saving cannot blank a field the administrator did not intend to touch.
                  setMetadataForm({
                    businessUnit: standard.businessUnit ?? "",
                    category: standard.category ?? "",
                    standardFamily: standard.standardFamily ?? "",
                    documentType: standard.documentType ?? "",
                    tags: (standard.tags ?? []).join(", "),
                  });
                  setMetadataTarget(standard);
                }}
              >
                Edit labels
              </Button>
              <Button
                size="small"
                onClick={() => {
                  setPendingFile(null);
                  setSelectedVersion(suggestVersion(standard.versions));
                  setUploadTarget({
                    standardId: standard.id,
                    // Suggests the next whole version rather than leaving it blank: a version LABEL
                    // cannot be reused with different content (§19.2), so an empty field invites a
                    // collision on the second upload.
                    version: suggestVersion(standard.versions),
                  });
                }}
              >
                Upload version
              </Button>
            </Stack>

            {standard.versions.length === 0 && (
              <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
                No versions uploaded yet.
              </Typography>
            )}

            <Stack spacing={1} sx={{ mt: 1 }}>
              {standard.versions.map((version) => (
                <Box key={version.id} sx={{ pl: 1, borderLeft: 2, borderColor: "divider" }}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
                    <Typography variant="body2" sx={{ fontWeight: 500 }}>
                      v{version.version}
                    </Typography>
                    <Chip
                      size="small"
                      label={version.indexingState.replaceAll("_", " ").toLowerCase()}
                      color={stateColor(version.indexingState)}
                      variant={version.indexingState === "INDEXED" ? "filled" : "outlined"}
                    />
                    <Chip size="small" variant="outlined" label={version.lifecycle.toLowerCase()} />

                    {version.candidateCount > 0 && (
                      <Tooltip title="Candidate sections the detector found in the document, before extraction.">
                        <Chip size="small" variant="outlined"
                          label={`${version.candidateCount} sections scanned`} />
                      </Tooltip>
                    )}

                    {version.requirementCount > 0 && (
                      <Tooltip title="Only material requirements are evaluated — advisory statements, definitions, and examples are excluded (§12.2).">
                        <Chip
                          size="small"
                          variant="outlined"
                          label={`${version.materialRequirementCount} of ${version.requirementCount} requirements evaluated`}
                        />
                      </Tooltip>
                    )}

                    <Box sx={{ flex: 1 }} />

                    {/* §8.1: activation is gated on INDEXED, so the button only appears once the
                        version actually has requirements to compare against. */}
                    {version.indexingState === "INDEXED" && version.lifecycle !== "ACTIVE" && (
                      <Button
                        size="small"
                        variant="outlined"
                        disabled={readOnly || activate.isPending}
                        onClick={() => activate.mutate(version.id)}
                      >
                        Activate
                      </Button>
                    )}
                    {version.comparable && (
                      <Chip size="small" color="success" label="ready for reviews" />
                    )}
                  </Stack>

                  {inProgress(version.indexingState) && (
                    <Box sx={{ mt: 0.5, mb: 0.5 }}>
                      <LinearProgress />
                      <Typography variant="caption" sx={{ color: "text.secondary" }}>
                        {/* The stage, not a spinner: extraction makes one model call per candidate
                            group and legitimately takes minutes (§20.1). */}
                        {stageLabel(version.indexingState)}
                      </Typography>
                    </Box>
                  )}

                  {/* Informational notes on a HEALTHY version. A section read as guidance produces no
                      requirement and is therefore never checked — correct per §12.2, but invisible to
                      an author who meant it to be binding unless it is said here. Shown as info, not
                      a warning: nothing failed. */}
                  {version.indexingState === "INDEXED" && version.extractionIssues.length > 0 && (
                    <Alert severity="info" sx={{ mt: 0.5 }}>
                      <Stack component="ul" sx={{ pl: 2, my: 0 }} spacing={0.5}>
                        {summariseIssues(version.extractionIssues).map((issue) => (
                          <IssueItem key={issue.guidance} issue={issue} />
                        ))}
                      </Stack>
                    </Alert>
                  )}

                  {version.indexingState === "NEEDS_REVIEW" && (
                    <Alert severity="warning" sx={{ mt: 0.5 }}>
                      <Typography variant="body2" sx={{ fontWeight: 500 }}>
                        {version.rejectedRequirementCount > 0
                          ? `${version.rejectedRequirementCount} of ${
                              version.rejectedRequirementCount + version.requirementCount
                            } extracted requirements could not be validated.`
                          : "Some requirements could not be validated."}{" "}
                        This version was not published and cannot be activated.
                      </Typography>

                      {/* The actual guidance. "Needs review" alone gives the person who owns the
                          document nothing to act on — the fix is nearly always an edit to their
                          source text, and only these lines say which edit. */}
                      {version.extractionIssues.length > 0 && (
                        <Stack component="ul" sx={{ pl: 2, mt: 1, mb: 0 }} spacing={0.75}>
                          {summariseIssues(version.extractionIssues).map((issue) => (
                          <IssueItem key={issue.guidance} issue={issue} />
                        ))}
                        </Stack>
                      )}

                      <Typography variant="caption" color="text.secondary"
                        sx={{ display: "block", mt: 1 }}>
                        Fix the document and upload it as a new version — a version label cannot be
                        reused with different content.
                      </Typography>
                    </Alert>
                  )}
                  {version.indexingState === "FAILED" && (
                    <Alert severity="error" sx={{ mt: 0.5, py: 0 }}>
                      Indexing failed. If this is a scanned PDF, its pages are images and no
                      requirements can be extracted.
                    </Alert>
                  )}
                </Box>
              ))}
            </Stack>
          </Paper>
        ))}
      </Stack>

      {/* ---- edit curated labels ---- */}
      <Dialog
        open={metadataTarget !== null}
        onClose={() => setMetadataTarget(null)}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Labels for {metadataTarget?.title}</DialogTitle>
        <DialogContent>
          <Typography sx={{ fontSize: 13, color: palette.muted, mb: 2 }}>
            These decide which uploaded documents this standard is matched to. They are ranking
            signals — a mismatch lowers this standard's position, it never hides it. Saved here and
            copied into the search index; a later re-index will not overwrite them.
          </Typography>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Department"
              size="small"
              value={metadataForm.businessUnit}
              onChange={(e) => setMetadataForm({ ...metadataForm, businessUnit: e.target.value })}
              helperText="The owning department, e.g. Information Security"
            />
            <TextField
              label="Standard family"
              size="small"
              value={metadataForm.standardFamily}
              onChange={(e) => setMetadataForm({ ...metadataForm, standardFamily: e.target.value })}
              helperText="Standards habitually assessed together, e.g. Access Control"
            />
            <TextField
              label="Document type"
              size="small"
              value={metadataForm.documentType}
              onChange={(e) => setMetadataForm({ ...metadataForm, documentType: e.target.value })}
              helperText="The kind of document this governs, e.g. Procedure"
            />
            <TextField
              label="Category"
              size="small"
              value={metadataForm.category}
              onChange={(e) => setMetadataForm({ ...metadataForm, category: e.target.value })}
              helperText="Subject area, e.g. Change Management"
            />
            <TextField
              label="Tags"
              size="small"
              value={metadataForm.tags}
              onChange={(e) => setMetadataForm({ ...metadataForm, tags: e.target.value })}
              helperText="Comma-separated. Specific beats generic: cab-approval over process."
            />
          </Stack>
          {/* Stated because the save is slower than it looks: every indexed version of this standard
              is re-embedded so control-level retrieval ranks on the same labels as document-level. */}
          {saveMetadata.isPending && (
            <Typography sx={{ fontSize: 12, color: palette.muted, mt: 2 }}>
              Saving and rebuilding the search index for this standard…
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setMetadataTarget(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={saveMetadata.isPending || metadataTarget === null}
            onClick={() => metadataTarget && saveMetadata.mutate(metadataTarget.id)}
          >
            {saveMetadata.isPending ? "Saving…" : "Save labels"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* ---- create standard ---- */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>New standard</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Standard key"
              required
              helperText="Stable identifier, e.g. GRC-CHG-2026. Used in findings and cannot be changed."
              value={form.standardKey}
              onChange={(e) => setForm({ ...form, standardKey: e.target.value })}
            />
            <TextField
              label="Title"
              required
              value={form.title}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
            />
            <TextField
              label="Owner"
              value={form.owner}
              onChange={(e) => setForm({ ...form, owner: e.target.value })}
            />
            <TextField
              label="Description"
              multiline
              minRows={2}
              helperText="Used to match this standard to uploaded documents during automatic selection (§15.1)."
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />
            {/* Category and tags feed the SAME matching vector as the title and description. Without
                them a standard is matched on its title alone, which is why two versions of
                "Technology Change Management Standard" were previously indistinguishable to automatic
                selection. Optional, but the most useful thing to fill in. */}
            <TextField
              label="Category"
              helperText="Subject area, e.g. Change Management. Improves automatic selection."
              value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })}
            />
            <TextField
              label="Tags"
              helperText="Comma-separated, e.g. change, deployment, approval."
              value={form.tags}
              onChange={(e) => setForm({ ...form, tags: e.target.value })}
            />
            <TextField
              label="Business unit"
              helperText="Recorded for reporting; not used for matching."
              value={form.businessUnit}
              onChange={(e) => setForm({ ...form, businessUnit: e.target.value })}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={readOnly || !form.standardKey.trim() || !form.title.trim()
              || createStandard.isPending}
            onClick={() => createStandard.mutate()}
          >
            {createStandard.isPending ? "Creating…" : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* ---- upload version ---- */}
      <Dialog
        open={uploadTarget !== null}
        onClose={() => { setUploadTarget(null); setPendingFile(null); }}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>Upload standard version</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Version label"
              required
              helperText="A label cannot be reused with different content — publish a new label instead."
              value={selectedVersion}
              onChange={(e) => setSelectedVersion(e.target.value)}
            />
            {/* A VISIBLE native file input, not a hidden one driven by ref.click().
                A hidden input inside a MUI Dialog lives in a portal that unmounts with the dialog,
                so the ref is unreliable — clicking "Choose file" silently did nothing and the upload
                never reached the server, while the standard itself had already been created. The
                native control also shows the selected filename, which a button cannot. */}
            <Button
              component="label"
              variant="outlined"
              disabled={readOnly || !selectedVersion.trim() || uploadVersion.isPending}
            >
              {uploadVersion.isPending ? "Uploading…" : "Choose file"}
              <input
                type="file"
                hidden
                accept={ACCEPTED_EXTENSIONS}
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) {
                    setPendingFile(file);
                  }
                  // Reset so re-picking the SAME file fires onChange again.
                  e.target.value = "";
                }}
              />
            </Button>

            {pendingFile && (
              <Typography variant="body2" color="text.secondary">
                {pendingFile.name} ({(pendingFile.size / 1024).toFixed(0)} KB)
              </Typography>
            )}

            {/* Upload is a SEPARATE, explicit action rather than firing on file selection. Picking a
                file and having the request leave immediately gives no chance to correct the version
                label, and no visible confirmation that anything was chosen. */}
            <Button
              variant="contained"
              disabled={readOnly || !pendingFile || !selectedVersion.trim()
                || uploadVersion.isPending}
              onClick={() => {
                if (pendingFile && uploadTarget) {
                  uploadVersion.mutate({
                    standardId: uploadTarget.standardId,
                    version: selectedVersion,
                    file: pendingFile,
                  });
                }
              }}
            >
              {uploadVersion.isPending ? "Uploading…" : "Upload"}
            </Button>
            <Alert severity="info" sx={{ py: 0 }}>
              After upload, the document is parsed and its requirements are extracted. This takes a
              few minutes and runs in the background — you can leave this page.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => { setUploadTarget(null); setPendingFile(null); }}>Close</Button>
        </DialogActions>
      </Dialog>

      {result && rows.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <PageControls
            page={result}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
            label="standards"
          />
        </Box>
      )}
    </Box>
  );
}

/**
 * Formats the pipeline can actually parse.
 *
 * Kept as one constant so the picker cannot drift from what the server accepts — an `accept` list
 * advertising a format the parser rejects produces an upload that succeeds and then fails
 * asynchronously, which reads as a system fault rather than an unsupported file.
 */
const ACCEPTED_EXTENSIONS = ".pdf,.docx,.txt,.md,.markdown,.csv,.json";

/** Non-terminal indexing states (§21). Drives both polling and the progress bar. */
function inProgress(state: string): boolean {
  return !["INDEXED", "NEEDS_REVIEW", "FAILED"].includes(state);
}

/** Human phrasing for the §21 administrator-visible states. */
function stageLabel(state: string): string {
  switch (state) {
    case "UPLOADED":
      return "Queued for parsing…";
    case "PARSING_STANDARD":
    case "RENDERING_STANDARD":
      return "Reading the document…";
    case "GENERATING_SPANS":
    case "GENERATING_CHUNKS":
      return "Indexing passages…";
    case "DETECTING_REQUIREMENT_CANDIDATES":
      return "Finding requirement candidates…";
    case "EXTRACTING_REQUIREMENTS":
      return "Extracting requirements (one model call per control)…";
    case "VALIDATING_REQUIREMENTS":
      return "Validating extracted requirements against source text…";
    case "GENERATING_EMBEDDINGS":
    case "INDEXING_STANDARD":
      return "Building the search index…";
    default:
      return state.replaceAll("_", " ").toLowerCase() + "…";
  }
}

function stateColor(state: string): "success" | "warning" | "error" | "default" {
  if (state === "INDEXED") return "success";
  if (state === "NEEDS_REVIEW") return "warning";
  if (state === "FAILED") return "error";
  return "default";
}

/**
 * Suggests the next unused whole version number.
 *
 * Avoids the most likely mistake on a second upload: reusing a label, which the API rejects because
 * two documents sharing a version make every historical finding ambiguous (§19.2).
 */
export function suggestVersion(versions: readonly StandardVersionSummary[]): string {
  // Compared segment by segment, not by parseFloat. parseFloat("2.4.1") is 2.4 — it silently drops
  // everything after the second dot — and Math.floor then suggested "3" for a standard at 2.4,
  // skipping 2.5 entirely. Worse, parseFloat treats "2" and "2.0" as equal, so the suggestion could
  // repeat a label the backend now (correctly) rejects as a duplicate.
  const parsed = versions
    .map((v) => v.version.trim().replace(/^v/i, ""))
    .filter((v) => /^\d+(\.\d+)*$/.test(v))
    .map((v) => v.split(".").map(Number));

  if (parsed.length === 0) {
    // No numeric versions to build on — including a standard whose only versions are labelled
    // "2024-Q1" or similar. "1" is a safe first suggestion; the field stays editable.
    return "1";
  }

  const highest = parsed.reduce((best, current) => {
    const length = Math.max(best.length, current.length);
    for (let i = 0; i < length; i++) {
      // A missing segment is zero, so 2.4 sorts below 2.4.1.
      const a = best[i] ?? 0;
      const b = current[i] ?? 0;
      if (a !== b) {
        return b > a ? current : best;
      }
    }
    return best;
  });

  // Increment the LAST segment, preserving the standard's own numbering depth: 2.4 -> 2.5, and
  // 2.4.1 -> 2.4.2. Bumping the major version instead discards a minor-version scheme the
  // organisation is deliberately using.
  const next = [...highest];
  next[next.length - 1] = (next[next.length - 1] ?? 0) + 1;
  return next.join(".");
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) {
    return error.message;
  }
  return "Something went wrong.";
}

/**
 * One extraction issue, with its location and the exact text it concerns.
 *
 * Shared by the NEEDS_REVIEW and INDEXED alerts so the two cannot drift — they previously duplicated
 * this markup and only one of them showed the quoted excerpt.
 *
 * The QUOTED SENTENCE is the important part. A section heading alone was ambiguous, because every
 * paragraph inside a section shares it: an author told "Clause 1" had to guess which sentence in that
 * section was dropped. The words are unambiguous.
 */
function IssueItem({ issue }: {
  issue: {
    guidance: string;
    requirementIds: string[];
    excerpts: string[];
    actionable: boolean;
  };
}) {
  return (
    <Box component="li">
      <Typography variant="body2">{issue.guidance}</Typography>

      {issue.excerpts.length > 0 ? (
        <Stack sx={{ mt: 0.5 }} spacing={0.25}>
          {issue.excerpts.slice(0, 4).map((text, i) => (
            <Typography key={text} variant="caption" color="text.secondary">
              {issue.requirementIds[i] ? `${issue.requirementIds[i]} — ` : ""}
              <Box component="span" sx={{ fontStyle: "italic" }}>“{text}”</Box>
            </Typography>
          ))}
          {issue.excerpts.length > 4 && (
            <Typography variant="caption" sx={{ color: "text.secondary" }}>
              and {issue.excerpts.length - 4} more
            </Typography>
          )}
        </Stack>
      ) : issue.requirementIds.length > 0 ? (
        <Typography variant="caption" sx={{ color: "text.secondary" }}>
          Affected: {issue.requirementIds.slice(0, 8).join(", ")}
          {issue.requirementIds.length > 8
            ? ` and ${issue.requirementIds.length - 8} more`
            : ""}
        </Typography>
      ) : null}    </Box>
  );
}