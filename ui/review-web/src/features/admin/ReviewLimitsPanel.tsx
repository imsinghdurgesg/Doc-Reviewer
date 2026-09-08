import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert, Box, Button, CircularProgress, Stack, TextField, Typography,
} from "@mui/material";
import { apiClient } from "@/api/client";
import { overlineLabel, palette } from "@/app/theme";
import { useCurrentUser } from "@/auth/AuthGate";

/**
 * Review limits. Architecture §10.5, §15.5.
 *
 * <strong>Why these are settings rather than constants.</strong> Both were compile-time values, so
 * tuning either meant a redeploy. The requirement cap in particular is the number an administrator
 * discovers is wrong at the moment a real review is rejected — the worst possible time to need a
 * release.
 *
 * <strong>Why the consequences are stated next to each field.</strong> Neither number is
 * self-explanatory, and both have a measured cost for getting them wrong: more recommended standards
 * duplicates findings, a higher requirement cap multiplies model calls. A form that just showed two
 * integers would invite raising them without knowing what it buys or costs.
 */

interface ReviewLimits {
  recommendedStandards: number;
  maxRequirementsPerComparison: number;
  maxAllowedRecommendedStandards: number;
  maxAllowedRequirementsPerComparison: number;
}

export function ReviewLimitsPanel() {
  const queryClient = useQueryClient();
  const { user } = useCurrentUser();
  // Writes are ADMIN only, matching the endpoint. A STANDARDS_ADMIN can read the cap — it is the
  // number that rejects their comparison — but cannot change it.
  const readOnly = !(user?.roles ?? []).includes("ADMIN");

  const limits = useQuery({
    queryKey: ["admin", "review-limits"],
    queryFn: () => apiClient.get<ReviewLimits>("/admin/review-limits"),
  });

  const [recommended, setRecommended] = useState("");
  const [maxRequirements, setMaxRequirements] = useState("");
  const [error, setError] = useState<string | null>(null);

  // Seeded from the server once loaded, not on every render: overwriting the fields while someone is
  // typing would discard their edit.
  useEffect(() => {
    if (limits.data) {
      setRecommended(String(limits.data.recommendedStandards));
      setMaxRequirements(String(limits.data.maxRequirementsPerComparison));
    }
  }, [limits.data]);

  const save = useMutation({
    mutationFn: () =>
      apiClient.put<ReviewLimits>("/admin/review-limits", {
        recommendedStandards: Number(recommended),
        maxRequirementsPerComparison: Number(maxRequirements),
      }),
    onSuccess: (saved) => {
      setError(null);
      // Re-seeded from the RESPONSE, not from the form. The server clamps an out-of-range value, so
      // showing what was typed would report a setting that is not in force.
      setRecommended(String(saved.recommendedStandards));
      setMaxRequirements(String(saved.maxRequirementsPerComparison));
      void queryClient.invalidateQueries({ queryKey: ["admin", "review-limits"] });
    },
    onError: (e: unknown) => setError(e instanceof Error ? e.message : "Could not save"),
  });

  if (limits.isLoading) {
    return (
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", p: 2 }}>
        <CircularProgress size={16} />
        <Typography sx={{ fontSize: 12.5 }}>Loading review limits…</Typography>
      </Stack>
    );
  }
  if (limits.isError || !limits.data) {
    return <Alert severity="error" sx={{ m: 2 }}>Could not load review limits.</Alert>;
  }

  const data = limits.data;
  const recommendedNum = Number(recommended);
  const requirementsNum = Number(maxRequirements);
  const recommendedInvalid =
    !Number.isInteger(recommendedNum) || recommendedNum < 1
    || recommendedNum > data.maxAllowedRecommendedStandards;
  const requirementsInvalid =
    !Number.isInteger(requirementsNum) || requirementsNum < 1
    || requirementsNum > data.maxAllowedRequirementsPerComparison;
  const changed =
    recommendedNum !== data.recommendedStandards
    || requirementsNum !== data.maxRequirementsPerComparison;

  return (
    <Box sx={{ p: 2, maxWidth: 720 }}>
      <Typography sx={{ ...overlineLabel, fontSize: 9, mb: 0.5 }}>Review limits</Typography>
      <Typography sx={{ fontSize: 12.5, color: palette.muted, mb: 2 }}>
        Both take effect on the next review. Nothing needs restarting, and existing reviews are
        unaffected — a completed review keeps the settings that produced it.
      </Typography>

      <TextField
        size="small"
        type="number"
        label="Recommended standards"
        value={recommended}
        disabled={readOnly || save.isPending}
        error={recommendedInvalid}
        onChange={(event) => setRecommended(event.target.value)}
        sx={{ width: 220, mb: 0.5 }}
        slotProps={{ htmlInput: { min: 1, max: data.maxAllowedRecommendedStandards } }}
      />
      <Typography sx={{ fontSize: 12, color: palette.muted, mb: 2.5 }}>
        How many standards arrive pre-selected when a reviewer uploads a document.
        <strong> Raising this duplicates findings:</strong> three standards derived from a common
        framework produced 118 findings where one produced 65 — the same control three times, with
        nothing to say whether that is three problems or one. Anything not pre-selected is still one
        click away. Cannot exceed the {data.maxAllowedRecommendedStandards} standard
        {data.maxAllowedRecommendedStandards === 1 ? "" : "s"} currently indexed — recommending more
        than exist would have no effect.
      </Typography>

      <TextField
        size="small"
        type="number"
        label="Max requirements per review"
        value={maxRequirements}
        disabled={readOnly || save.isPending}
        error={requirementsInvalid}
        onChange={(event) => setMaxRequirements(event.target.value)}
        sx={{ width: 220, mb: 0.5 }}
        slotProps={{ htmlInput: { min: 1, max: data.maxAllowedRequirementsPerComparison } }}
      />
      <Typography sx={{ fontSize: 12, color: palette.muted, mb: 2.5 }}>
        The ceiling on one review. Each requirement is a model call, so this is what one review can
        cost. A selection exceeding it is <strong>refused up front with the count</strong> — never
        silently trimmed, because a review that quietly dropped a third of its requirements while
        reporting “completed” is worse than one that refuses. A 10-page standard yields roughly 60
        requirements. Maximum {data.maxAllowedRequirementsPerComparison}.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
      {readOnly && (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          These are shown for reference. Changing them requires an administrator.
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Button
          variant="contained"
          size="small"
          disabled={
            readOnly || !changed || recommendedInvalid || requirementsInvalid || save.isPending
          }
          onClick={() => save.mutate()}
        >
          {save.isPending ? "Saving…" : "Save"}
        </Button>
        {save.isSuccess && !changed && (
          <Typography sx={{ fontSize: 12, color: palette.muted }}>Saved</Typography>
        )}
      </Stack>
    </Box>
  );
}
