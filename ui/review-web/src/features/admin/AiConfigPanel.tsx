import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import NetworkCheckIcon from "@mui/icons-material/NetworkCheck";
import { apiClient } from "@/api/client";
import { useCurrentUser } from "@/auth/AuthGate";
import { canAdminister } from "@/auth/session";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Model provider settings. Architecture §10, §30.
 *
 * <p><strong>The API key is write-only from the browser's point of view.</strong> The server reports
 * whether one is stored, never what it is, so this form cannot pre-fill the field — and a blank
 * value on save therefore means "keep the existing key" rather than "clear it". Without that rule an
 * administrator changing a model name would wipe the credential just by leaving the field alone.
 */

interface AiConfig {
  provider: string | null;
  baseUrl: string | null;
  apiKeySet: boolean;
  /** False when DOCREVIEW_SECRET_KEY is unset, so the server cannot store a key at all. */
  secretKeyReady: boolean;
  comparisonModel: string | null;
  extractionModel: string | null;
  embeddingModel: string | null;
  comparisonInstructions: string | null;
  extractionInstructions: string | null;
  profileInstructions: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  lastTestOk: boolean | null;
  lastTestMessage: string | null;
  lastTestedAt: string | null;
}

/** Matches the server's cap. Enforced here so the limit is visible while typing, not on submit. */
const INSTRUCTIONS_LIMIT = 32000;

export function AiConfigPanel() {
  // Disabled, not hidden: see StandardsAdminPage. The backend rejects the write regardless, so
  // rendering the form read-only tells a reviewer what exists without granting anything.
  const { user } = useCurrentUser();
  const readOnly = !canAdminister(user);
  const queryClient = useQueryClient();
  const config = useQuery({
    queryKey: ["ai-config"],
    queryFn: () => apiClient.get<AiConfig>("/admin/ai-config"),
  });

  const [form, setForm] = useState({
    provider: "litellm",
    baseUrl: "",
    apiKey: "",
    comparisonModel: "",
    extractionModel: "",
    embeddingModel: "",
    comparisonInstructions: "",
    extractionInstructions: "",
    profileInstructions: "",
  });

  // Seeded from the server once loaded. The key is deliberately absent — it is never sent back.
  useEffect(() => {
    const data = config.data;
    if (!data) {
      return;
    }
    setForm({
      provider: data.provider ?? "litellm",
      baseUrl: data.baseUrl ?? "",
      apiKey: "",
      comparisonModel: data.comparisonModel ?? "",
      extractionModel: data.extractionModel ?? "",
      embeddingModel: data.embeddingModel ?? "",
      comparisonInstructions: data.comparisonInstructions ?? "",
      extractionInstructions: data.extractionInstructions ?? "",
      profileInstructions: data.profileInstructions ?? "",
    });
  }, [config.data]);

  const save = useMutation({
    mutationFn: () => apiClient.put<AiConfig>("/admin/ai-config", form),
    onSuccess: (data) => queryClient.setQueryData(["ai-config"], data),
  });

  const test = useMutation({
    mutationFn: () => apiClient.post<AiConfig>("/admin/ai-config/test", {}),
    onSuccess: (data) => queryClient.setQueryData(["ai-config"], data),
  });

  if (config.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", p: 4 }}>
        <CircularProgress />
      </Stack>
    );
  }

  const data = config.data;
  const update = (patch: Partial<typeof form>) =>
    setForm((current) => ({ ...current, ...patch }));

  return (
    <Box sx={{ bgcolor: "#fff", border: 1, borderColor: "divider", borderRadius: 1 }}>
      <Box sx={{ p: 3, borderBottom: 1, borderColor: "divider" }}>
        <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.4px" }}>
          LLM gateway
        </Typography>
        <Typography sx={{ fontSize: 13, color: palette.muted, mt: 0.5 }}>
          Used for requirement extraction, comparison, and embeddings.
        </Typography>
      </Box>

      <Stack spacing={3} sx={{ p: 3 }}>
        {readOnly && (
          <Alert severity="info">
            Model settings are read-only for your role. Changing the provider, key, model aliases, or
            instructions requires the Admin role.
          </Alert>
        )}

        {!data?.secretKeyReady && (
          <Alert severity="warning">
            {/* Named explicitly: without it, saving a key fails with a message the administrator
                cannot act on from this screen. */}
            The server has no encryption key configured, so an API key cannot be stored. Set
            DOCREVIEW_SECRET_KEY (<code>openssl rand -base64 32</code>) and restart.
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

        <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", rowGap: 2 }}>
          <TextField
            disabled={readOnly}
            select label="Provider" size="small" sx={{ minWidth: 220 }}
            value={form.provider}
            onChange={(event) => update({ provider: event.target.value })}
          >
            <MenuItem value="litellm">LiteLLM gateway</MenuItem>
            <MenuItem value="openai">OpenAI-compatible</MenuItem>
          </TextField>

          <TextField
            disabled={readOnly}
            label="Server URL" size="small" sx={{ flex: 1, minWidth: 320 }}
            value={form.baseUrl}
            onChange={(event) => update({ baseUrl: event.target.value })}
            placeholder="https://aiapi.example.edu"
            helperText="The proxy root, WITHOUT /v1 — the client appends it."
          />
        </Stack>

        <TextField
          disabled={readOnly}
          label="API key" size="small" type="password"
          value={form.apiKey}
          onChange={(event) => update({ apiKey: event.target.value })}
          placeholder={data?.apiKeySet ? "Leave blank to keep the existing key" : "Not set"}
          helperText={
            data?.apiKeySet
              ? "A key is stored. It is encrypted at rest and never returned to the browser; leave blank to keep it."
              : "Encrypted at rest and never returned to the browser."
          }
          slotProps={{
            input: {
              endAdornment: data?.apiKeySet ? (
                <Chip
                  size="small" label="SET"
                  sx={{ bgcolor: palette.statusAligned.bg, color: palette.statusAligned.fg,
                        fontWeight: 700, fontSize: 10 }}
                />
              ) : null,
            },
          }}
        />

        <Divider />

        <Typography sx={overlineLabel}>Model aliases</Typography>
        <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", rowGap: 2 }}>
          <TextField
            disabled={readOnly}
            label="Comparison model" size="small" sx={{ flex: 1, minWidth: 220 }}
            value={form.comparisonModel}
            onChange={(event) => update({ comparisonModel: event.target.value })}
          />
          <TextField
            disabled={readOnly}
            label="Extraction model" size="small" sx={{ flex: 1, minWidth: 220 }}
            value={form.extractionModel}
            onChange={(event) => update({ extractionModel: event.target.value })}
          />
          <TextField
            disabled={readOnly}
            label="Embedding model" size="small" sx={{ flex: 1, minWidth: 220 }}
            value={form.embeddingModel}
            onChange={(event) => update({ embeddingModel: event.target.value })}
          />
        </Stack>

        <Divider />

        <Box>
          <Typography sx={overlineLabel}>Comparison instructions</Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mt: 0.5, mb: 1.5 }}>
            Guidance for how the model judges alignment. The response schema, evidence-only rule and
            prompt-injection guard are appended after this text, so custom guidance cannot remove the
            constraints that keep findings citable.
          </Typography>
          <TextField
            disabled={readOnly}
            multiline minRows={5} fullWidth size="small"
            value={form.comparisonInstructions}
            onChange={(event) =>
              update({ comparisonInstructions: event.target.value.slice(0, INSTRUCTIONS_LIMIT) })
            }
            helperText={`${form.comparisonInstructions.length} / ${INSTRUCTIONS_LIMIT}`}
          />
        </Box>

        {/* One field per prompt, not one shared box. Guidance for judging compliance has nothing to
            do with guidance for splitting a standard into requirements, and a single field would
            apply every instruction to all three calls. The Prompts tab shows what each attaches to. */}
        <Box>
          <Typography sx={overlineLabel}>Requirement extraction instructions</Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mb: 1 }}>
            How a standard is split into individually checkable requirements — for example whether a
            clause listing three obligations becomes one requirement or three.
          </Typography>
          <TextField
            multiline minRows={4} fullWidth size="small"
            disabled={readOnly}
            value={form.extractionInstructions}
            onChange={(event) =>
              update({ extractionInstructions: event.target.value.slice(0, INSTRUCTIONS_LIMIT) })
            }
            helperText={`${form.extractionInstructions.length} / ${INSTRUCTIONS_LIMIT}`}
          />
        </Box>

        <Box>
          <Typography sx={overlineLabel}>Standard profile instructions</Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mb: 1 }}>
            How a standard is described and categorised, which decides which standard an uploaded
            document is matched to. Useful for imposing your own category vocabulary.
          </Typography>
          <TextField
            multiline minRows={4} fullWidth size="small"
            disabled={readOnly}
            value={form.profileInstructions}
            onChange={(event) =>
              update({ profileInstructions: event.target.value.slice(0, INSTRUCTIONS_LIMIT) })
            }
            helperText={`${form.profileInstructions.length} / ${INSTRUCTIONS_LIMIT}`}
          />
        </Box>
      </Stack>

      <Stack
        direction="row"
        spacing={2}
        sx={{ p: 3, pt: 0, justifyContent: "flex-end", alignItems: "center" }}
      >
        {data?.updatedAt && (
          <Typography variant="caption" sx={{ color: palette.muted, mr: "auto" }}>
            Last saved {new Date(data.updatedAt).toLocaleString()}
            {data.updatedBy ? ` by ${data.updatedBy}` : ""}
          </Typography>
        )}
        <Button
          startIcon={<NetworkCheckIcon />}
          onClick={() => test.mutate()}
          disabled={readOnly || test.isPending || save.isPending}
        >
          {test.isPending ? "Testing…" : "Test connection"}
        </Button>
        <Button
          variant="contained"
          onClick={() => save.mutate()}
          disabled={readOnly || save.isPending}
          sx={{ minHeight: 40, px: 2.5 }}
        >
          {save.isPending ? "Saving…" : "Save"}
        </Button>
      </Stack>
    </Box>
  );
}
