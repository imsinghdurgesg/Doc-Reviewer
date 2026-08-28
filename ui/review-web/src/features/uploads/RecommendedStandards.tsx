import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Collapse,
  Link,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { useDebounced } from "@/hooks/useDebounced";
import { AssessmentFocus } from "@/features/comparisons/AssessmentFocus";
import { palette } from "@/app/theme";

/**
 * Recommended standards for an uploaded document. Architecture §15.1.
 *
 * <p>The comparison does not begin here. In REVIEW mode the reviewer must see what would be measured
 * against before committing — a recommendation acted on without being shown is indistinguishable from
 * the tool having chosen alone, and the reviewer carries the finding.
 *
 * <p><strong>Rejected candidates are shown, not hidden.</strong> §15.1 requires a reviewer to be able
 * to see that a standard was considered and why it was not used. A standard demoted for a region or
 * department mismatch is exactly the one a reviewer may know does apply, and it stays one click away.
 *
 * <p><strong>Relevance is not compliance.</strong> Scores say "worth comparing against", never
 * "complies with" — two passages can be near-identical and contradict each other. The wording here is
 * deliberately about relevance only.
 */

interface Candidate {
  standardVersionId: string;
  standardKey: string;
  title: string;
  department: string | null;
  standardFamily: string | null;
  documentType: string | null;
  tags: string[];
  selected: boolean;
  rejectionReason: string | null;
}

/**
 * What the platform inferred about the document, for the reviewer to confirm or correct.
 *
 * <p>`tier` drives presentation only — the backend already bounds how much these values can influence
 * ranking, whatever the confidence. A LOW tier asks the reviewer to check; it does not change the maths.
 */
interface InferredContext {
  department: string | null;
  standardFamily: string | null;
  documentType: string | null;
  confidence: number;
  tier: "NONE" | "LOW" | "MEDIUM" | "HIGH";
}

export interface AssessmentContextValue {
  department: string;
  standardFamily: string;
  documentType: string;
  guidance: string;
}

interface Props {
  documentVersionId: string;
  context: AssessmentContextValue;
  onContextChange: (context: AssessmentContextValue) => void;
  selectedVersionIds: string[];
  onToggle: (versionId: string) => void;
}

export function RecommendedStandards({
  documentVersionId,
  context,
  onContextChange,
  selectedVersionIds,
  onToggle,
}: Props) {
  const [showOthers, setShowOthers] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);

  // Debounced: editing the context re-runs retrieval, and a request per keystroke would both cost
  // embeddings and let responses land out of order.
  const debouncedContext = useDebounced(JSON.stringify(context), 400);

  const candidates = useQuery({
    queryKey: ["standard-candidates", documentVersionId, debouncedContext],
    queryFn: () => {
      const params = new URLSearchParams({ limit: "8" });
      if (context.department) params.set("department", context.department);
      if (context.standardFamily) params.set("standardFamily", context.standardFamily);
      if (context.documentType) params.set("documentType", context.documentType);
      if (context.guidance) params.set("guidance", context.guidance);
      return apiClient.get<{
        candidates: Candidate[];
        message?: string;
        inferred?: InferredContext;
      }>(`/comparisons/candidates/${documentVersionId}?${params}`);
    },
    enabled: Boolean(documentVersionId),
    staleTime: 0,
  });

  const inferred = candidates.data?.inferred;
  const all = candidates.data?.candidates ?? [];
  const recommended = all.filter((candidate) => candidate.selected);
  const others = all.filter((candidate) => !candidate.selected);

  const update = (patch: Partial<AssessmentContextValue>) =>
    onContextChange({ ...context, ...patch });

  return (
    <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "#fff", mb: 3 }}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: "center", p: 2, borderBottom: 1, borderColor: "divider" }}
      >
        <Box sx={{ flex: 1 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 600 }}>Recommended standards</Typography>
          <Typography sx={{ fontSize: 12, color: palette.muted }}>
            Ranked by relevance to this document. Relevance is not compliance — it means worth
            comparing against.
          </Typography>
        </Box>
        {candidates.isFetching && <CircularProgress size={18} />}
      </Stack>

      {/* What the platform assumed, stated plainly. Architecture §15.1.

          Applied on an explicit click rather than written into the fields automatically. A silent
          prefill would fight the reviewer's typing on every re-fetch, and — more importantly — a value
          that appears in an input box reads as something the reviewer entered and is accountable for.
          These are the platform's guesses, and they stay labelled as such until accepted. */}
      {inferred && inferred.tier !== "NONE" && (
        <Alert
          severity={inferred.tier === "HIGH" ? "success" : "info"}
          sx={{ m: 2, mb: 0, alignItems: "center" }}
          action={
            <Button
              size="small"
              onClick={() => {
                // Fills only what is still blank, so clicking this can never discard a typed value.
                setContextOpen(true);
                onContextChange({
                  ...context,
                  department: context.department || (inferred.department ?? ""),
                  standardFamily: context.standardFamily || (inferred.standardFamily ?? ""),
                  documentType: context.documentType || (inferred.documentType ?? ""),
                });
              }}
            >
              Use these
            </Button>
          }
        >
          <Typography sx={{ fontSize: 13, mb: 0.5 }}>
            {inferred.tier === "HIGH"
              ? "This looks like:"
              : "This might be — worth checking:"}
          </Typography>
          <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", rowGap: 0.5 }}>
            {inferred.department && (
              <Chip size="small" label={inferred.department} sx={{ height: 20, fontSize: 11 }} />
            )}
            {inferred.standardFamily && (
              <Chip size="small" label={inferred.standardFamily} sx={{ height: 20, fontSize: 11 }} />
            )}
            {inferred.documentType && (
              <Chip size="small" label={inferred.documentType} sx={{ height: 20, fontSize: 11 }} />
            )}
          </Stack>
        </Alert>
      )}

      {/* Context first: it changes the ranking below, so editing it after choosing would invalidate
          the choice. Collapsed by default — most reviewers will not need it, and an open form of four
          optional fields reads as work required before proceeding. */}
      <Box sx={{ px: 2, py: 1.5, borderBottom: 1, borderColor: "divider" }}>
        <Link
          component="button"
          type="button"
          onClick={() => setContextOpen((open) => !open)}
          sx={{ fontSize: 13, textDecoration: "none" }}
        >
          {contextOpen
            ? "Hide assessment context"
            : context.guidance.trim()
              ? "Edit assessment context"
              : "Add assessment context (optional)"}
        </Link>
        <Collapse in={contextOpen}>
          <Typography sx={{ fontSize: 12, color: palette.muted, mt: 1, mb: 1.5 }}>
            These improve the ranking. They never exclude a standard — anything ruled out stays listed
            below so you can include it.
          </Typography>
          <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", rowGap: 2 }}>
            <TextField
              size="small" label="Department" sx={{ minWidth: 200 }}
              value={context.department}
              onChange={(event) => update({ department: event.target.value })}
            />
            <TextField
              size="small" label="Standard family" sx={{ minWidth: 200 }}
              value={context.standardFamily}
              onChange={(event) => update({ standardFamily: event.target.value })}
            />
            <TextField
              size="small" label="Document type" sx={{ minWidth: 220 }}
              value={context.documentType}
              onChange={(event) => update({ documentType: event.target.value })}
            />
          </Stack>
          <TextField
            size="small" fullWidth multiline minRows={2} sx={{ mt: 2 }}
            label="Assessment details"
            placeholder="e.g. covers hardware procurement for the India office; excludes SaaS purchases"
            value={context.guidance}
            onChange={(event) => update({ guidance: event.target.value })}
            helperText="Used to steer which parts of the standards are searched. Retained with the review."
          />
        </Collapse>

        {/* Read-only echo while the form is COLLAPSED (§19.2).
            A reviewer who types a focus and then collapses the panel would otherwise start a
            comparison with no visible sign that retrieval had been narrowed — and the focus changes
            which findings exist. Hidden while the panel is open, where the editable field already
            shows it and a duplicate would just be noise. */}
        {!contextOpen && <AssessmentFocus guidance={context.guidance} variant="inline" />}
      </Box>

      {candidates.isLoading ? (
        <Stack sx={{ alignItems: "center", p: 3 }}>
          <CircularProgress size={22} />
        </Stack>
      ) : candidates.data?.message ? (
        <Alert severity="info" sx={{ m: 2 }}>{candidates.data.message}</Alert>
      ) : all.length === 0 ? (
        <Alert severity="info" sx={{ m: 2 }}>
          No standard in the library was relevant enough to recommend. You can still choose one
          manually below — comparing against an unrelated standard reports every requirement as a gap,
          so pick deliberately.
        </Alert>
      ) : (
        <>
          <Stack sx={{ p: 1 }}>
            {recommended.map((candidate) => (
              <CandidateRow
                key={candidate.standardVersionId}
                candidate={candidate}
                checked={selectedVersionIds.includes(candidate.standardVersionId)}
                onToggle={() => onToggle(candidate.standardVersionId)}
              />
            ))}
          </Stack>

          {others.length > 0 && (
            <Box sx={{ px: 2, pb: 2 }}>
              <Link
                component="button"
                type="button"
                onClick={() => setShowOthers((open) => !open)}
                sx={{ fontSize: 13, textDecoration: "none" }}
              >
                {showOthers
                  ? "Hide other standards considered"
                  : `Other standards considered (${others.length})`}
              </Link>
              <Collapse in={showOthers}>
                {/* The reason each was set aside, verbatim from the service. A reviewer who expected a
                    standard to appear needs to know it was seen and on what basis it was not used. */}
                <Stack sx={{ mt: 1 }}>
                  {others.map((candidate) => (
                    <CandidateRow
                      key={candidate.standardVersionId}
                      candidate={candidate}
                      checked={selectedVersionIds.includes(candidate.standardVersionId)}
                      onToggle={() => onToggle(candidate.standardVersionId)}
                      muted
                    />
                  ))}
                </Stack>
              </Collapse>
            </Box>
          )}
        </>
      )}
    </Box>
  );
}

function CandidateRow({
  candidate,
  checked,
  onToggle,
  muted = false,
}: {
  candidate: Candidate;
  checked: boolean;
  onToggle: () => void;
  muted?: boolean;
}) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{
        alignItems: "flex-start",
        px: 1,
        py: 1,
        borderRadius: 1,
        opacity: muted ? 0.75 : 1,
        "&:hover": { bgcolor: palette.rowHover },
      }}
    >
      <Checkbox size="small" checked={checked} onChange={onToggle} sx={{ mt: -0.5 }} />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{candidate.title}</Typography>
        <Typography sx={{ fontSize: 11, color: palette.muted }}>{candidate.standardKey}</Typography>

        {/* WHY it ranked where it did. Without this the order is unexplainable, and an unexplainable
            recommendation is one a reviewer either trusts blindly or ignores. */}
        <Stack direction="row" spacing={0.5} sx={{ mt: 0.5, flexWrap: "wrap", rowGap: 0.5 }}>
          {candidate.department && (
            <Chip size="small" label={candidate.department}
              sx={{ height: 19, fontSize: 10, fontWeight: 600 }} />
          )}
          {candidate.documentType && (
            <Chip size="small" variant="outlined" label={candidate.documentType}
              sx={{ height: 19, fontSize: 10 }} />
          )}
          {(candidate.tags ?? []).slice(0, 4).map((tag) => (
            <Chip key={tag} size="small" variant="outlined" label={tag}
              sx={{ height: 19, fontSize: 10, color: palette.muted }} />
          ))}
        </Stack>

        {candidate.rejectionReason && (
          <Typography sx={{ fontSize: 11, color: palette.muted, mt: 0.5, fontStyle: "italic" }}>
            {candidate.rejectionReason}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}
