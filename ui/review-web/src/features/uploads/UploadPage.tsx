import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  FormControlLabel,
  LinearProgress,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import CloudUploadOutlinedIcon from "@mui/icons-material/CloudUploadOutlined";
import { overlineLabel, palette } from "@/app/theme";
import { ApiError, apiClient } from "@/api/client";
import { RecommendedStandards } from "./RecommendedStandards";
import type { AssessmentContextValue } from "./RecommendedStandards";
import type {
  ComparisonDetail,
  StandardSummary,
  UploadedDocumentSummary,
} from "@/models/comparison";

const EMPTY_CONTEXT: AssessmentContextValue = {
  department: "",
  standardFamily: "",
  documentType: "",
  guidance: "",
};

/**
 * Upload a process document and start a review. Architecture §14, §20.2.
 *
 * This is the entry point to the whole flow: upload, wait for parsing, pick standards, compare.
 *
 * Parsing is asynchronous (§21), so the page polls the uploaded document until it reports
 * `readyForComparison`. The "Compare" button stays disabled until then — the backend would reject an
 * early comparison anyway, but a disabled button explains the wait, whereas an error after clicking
 * reads as a fault.
 */
export function UploadPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [document, setDocument] = useState<UploadedDocumentSummary | null>(null);
  const [selectedVersionIds, setSelectedVersionIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  /** Drag-over state, for the dropzone tint. */
  const [dragging, setDragging] = useState(false);
  /**
   * Reviewer-supplied assessment context (§15.1). Ranking signals plus free-text guidance — never
   * filters, so a wrong value cannot hide a standard from the list.
   */
  const [context, setContext] = useState<AssessmentContextValue>(EMPTY_CONTEXT);

  /**
   * Polls the document while parsing runs.
   *
   * Stops as soon as it is ready: an interval that keeps firing after the terminal state is reached
   * is a request every two seconds, forever, for every open tab.
   */
  const documentStatus = useQuery({
    queryKey: ["upload", document?.documentVersionId],
    queryFn: () =>
      apiClient.get<UploadedDocumentSummary>(`/uploads/${document?.documentVersionId}`),
    enabled: Boolean(document && !document.readyForComparison),
    refetchInterval: (query) =>
      query.state.data?.readyForComparison || query.state.data?.state === "FAILED" ? false : 2000,
  });

  // The polled copy is authoritative once it arrives; the upload response is only the first snapshot.
  const currentDocument = documentStatus.data ?? document;

  const standards = useQuery({
    queryKey: ["standards"],
    queryFn: () => apiClient.get<StandardSummary[]>("/admin/standards"),
  });

  const upload = useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return apiClient.upload<UploadedDocumentSummary>("/uploads", formData);
    },
    onSuccess: (result) => {
      setDocument(result);
      setError(null);
      // A new document invalidates both. Carrying the previous document's standards over would start a
      // comparison against standards the reviewer chose for a different file, and carrying its context
      // over would silently steer this file's retrieval — both invisible on screen.
      setSelectedVersionIds([]);
      setContext(EMPTY_CONTEXT);
    },
    onError: (e) => setError(messageOf(e)),
  });

  const createComparison = useMutation({
    mutationFn: (documentVersionId: string) =>
      apiClient.post<ComparisonDetail>("/comparisons", {
        documentVersionId,
        standardVersionIds: selectedVersionIds,
        // Retained with the review (§19.2): the guidance changes which requirements are surfaced, so a
        // finding cannot be interpreted later without knowing what the reviewer asked for.
        comparisonGuidance: context.guidance.trim() || null,
      }),
    onSuccess: (comparison) => {
      // Invalidate history so the new comparison appears without a manual refresh.
      void queryClient.invalidateQueries({ queryKey: ["comparisons"] });
      navigate(`/comparisons/${comparison.publicId}`);
    },
    onError: (e) => setError(messageOf(e)),
  });

  /** Only ACTIVE+INDEXED versions are offered (§8.1). */
  const comparableVersions = (standards.data ?? []).flatMap((standard) =>
    standard.versions
      .filter((version) => version.comparable)
      .map((version) => ({ standard, version })),
  );

  const toggleVersion = (versionId: string) =>
    setSelectedVersionIds((current) =>
      current.includes(versionId)
        ? current.filter((id) => id !== versionId)
        : [...current, versionId],
    );

  const parsing = Boolean(currentDocument && !currentDocument.readyForComparison);
  const parseFailed = currentDocument?.state === "FAILED";

  return (
    <Box sx={{ maxWidth: 880, mx: "auto", p: 4 }}>
      <Typography sx={{ fontSize: 27, fontWeight: 700, letterSpacing: "-0.7px", mb: 0.75 }}>
        Review a document
      </Typography>
      <Typography sx={{ fontSize: 14, color: palette.muted, mb: 4, lineHeight: 1.55 }}>
        Upload a process, procedure, policy, or SOP and compare it against your organizational
        standards.
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper
        variant="outlined"
        onDragOver={(event) => {
          // preventDefault on BOTH dragover and drop, or the browser navigates to the file instead
          // of handing it to the handler.
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          const file = event.dataTransfer.files?.[0];
          if (file && !upload.isPending) {
            setDocument(null);
            upload.mutate(file);
          }
        }}
        sx={{
          mb: 4,
          p: 4,
          borderStyle: "dashed",
          borderWidth: 1.5,
          borderColor: dragging ? palette.forest : "#aabbb1",
          bgcolor: dragging ? "#edf4ef" : "#f7f9f6",
          textAlign: "center",
          transition: "border-color .2s ease, background-color .2s ease",
        }}
      >
        <Stack sx={{ alignItems: "center" }}>
          <Box
            sx={{
              display: "grid",
              placeItems: "center",
              width: 54,
              height: 54,
              borderRadius: "50%",
              bgcolor: palette.mint,
              color: palette.forest,
              mb: 2.25,
            }}
          >
            <CloudUploadOutlinedIcon />
          </Box>
          <Typography sx={{ fontSize: 16, fontWeight: 700, mb: 0.5 }}>
            Drop your process document here
          </Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mb: 2.75 }}>
            PDF, DOCX, TXT, Markdown, CSV or JSON, up to 50 MB
          </Typography>
          <input
            ref={fileInputRef}
            type="file"
            hidden
            // Mirrors the server's supported list (§8.2). An accept filter is a convenience, not a
            // control: the server validates the file signature regardless.
            accept=".pdf,.docx,.doc,.txt,.md,.markdown,.csv,.json"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                setDocument(null);
                upload.mutate(file);
              }
              // Reset so re-picking the SAME file fires onChange again.
              event.target.value = "";
            }}
          />
          <Button
            variant="contained"
            onClick={() => fileInputRef.current?.click()}
            disabled={upload.isPending}
            sx={{ minHeight: 43, px: 2.5, fontSize: 13 }}
          >
            {upload.isPending ? "Uploading…" : "Choose document"}
          </Button>

          {currentDocument && (
            <Stack sx={{ minWidth: 0 }}>
              <Typography variant="body2" noWrap>
                {currentDocument.filename}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {(currentDocument.sizeBytes / 1024).toFixed(0)} KB
                {currentDocument.pageCount ? ` · ${currentDocument.pageCount} pages` : ""}
                {currentDocument.spanCount ? ` · ${currentDocument.spanCount} passages` : ""}
              </Typography>
            </Stack>
          )}
        </Stack>

        {parsing && !parseFailed && (
          <Box sx={{ mt: 2 }}>
            <LinearProgress />
            <Typography variant="caption" color="text.secondary">
              {/* The specific stage, not a generic spinner: parsing a large PDF takes minutes, and
                  §21 makes the stage visible so slow is distinguishable from stuck. */}
              {currentDocument?.state.replaceAll("_", " ").toLowerCase()}…
            </Typography>
          </Box>
        )}

        {parseFailed && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {currentDocument?.stateMessage ?? "This document could not be processed."}
          </Alert>
        )}

        {currentDocument?.readyForComparison && currentDocument.spanCount === 0 && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            No text was extracted from this document. If it is a scanned PDF, its pages are images
            and there is nothing to compare.
          </Alert>
        )}
      </Paper>

      {/* Recommendations, and the reviewer's chance to correct them, BEFORE the comparison starts
          (§15.1). Only once parsing is done: candidates are derived from the document's evidence
          spans, so asking earlier would return "still processing" and read as a failure. */}
      {/* `spanCount` is nullable while parsing; `?? 0` keeps a not-yet-known count from being read as
          "has spans". A zero-span document already shows its own warning above. */}
      {currentDocument?.readyForComparison && (currentDocument.spanCount ?? 0) > 0 && (
        <RecommendedStandards
          documentVersionId={currentDocument.documentVersionId}
          context={context}
          onContextChange={setContext}
          selectedVersionIds={selectedVersionIds}
          onToggle={toggleVersion}
        />
      )}

      <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.4px", mb: 1.5 }}>
        All standards
      </Typography>
      <Typography sx={{ fontSize: 13, color: palette.muted, mb: 1.5 }}>
        Browse the full library if the standard you need was not recommended above.
      </Typography>

      {standards.isLoading && <CircularProgress size={20} />}

      {!standards.isLoading && comparableVersions.length === 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {/* An unindexed standard has no requirements, so comparing against it yields no findings
              and reads as full compliance (§8.1). Saying why is more useful than an empty list. */}
          No standards are ready to compare against. A standard must be active and indexed before it
          can be used in a review.
        </Alert>
      )}

      <Stack sx={{ mb: 3, borderTop: 1, borderColor: "divider" }}>
        {comparableVersions.map(({ standard, version }) => (
          <FormControlLabel
            key={version.id}
            control={
              <Checkbox
                checked={selectedVersionIds.includes(version.id)}
                onChange={() => toggleVersion(version.id)}
              />
            }
            label={
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                  {standard.title}
                </Typography>
                <Typography sx={{ ...overlineLabel, fontSize: 9 }}>
                  v{version.version}
                </Typography>
                <Chip
                  size="small"
                  variant="outlined"
                  // Material count, not total: only material requirements are evaluated (§12.2), so
                  // showing the total would overstate what the review actually checks.
                  label={`${version.materialRequirementCount} requirements`}
                />
              </Stack>
            }
          />
        ))}
      </Stack>

      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Button
          variant="contained"
          sx={{ minHeight: 43, px: 2.5, fontSize: 13 }}
          // Selection is OPTIONAL. An empty selection asks the platform to find the governing
          // standard by relevance (§15.1), which is the question a reviewer uploading a procedure
          // usually has — they know the process, not which policy covers it.
          disabled={!currentDocument?.readyForComparison || createComparison.isPending}
          onClick={() =>
            currentDocument && createComparison.mutate(currentDocument.documentVersionId)
          }
        >
          {createComparison.isPending
            ? "Starting…"
            : selectedVersionIds.length === 0
              ? "Find standards & compare"
              : "Compare"}
        </Button>

        {/* States what will happen rather than demanding an action. The previous copy read
            "Select at least one standard", which was both a blocker and untrue. */}
        {currentDocument?.readyForComparison && selectedVersionIds.length === 0 && (
          <Typography variant="caption" color="text.secondary">
            No standard selected — the most relevant will be found automatically.
          </Typography>
        )}
        {parsing && !parseFailed && (
          <Typography variant="caption" color="text.secondary">
            Waiting for the document to finish processing.
          </Typography>
        )}
      </Stack>
    </Box>
  );
}

/** Prefers the server's message; falls back to something a user can act on. */
function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Something went wrong.";
}
