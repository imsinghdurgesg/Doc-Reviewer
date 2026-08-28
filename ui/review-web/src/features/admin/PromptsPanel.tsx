import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  Box,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import { apiClient } from "@/api/client";
import { overlineLabel, palette } from "@/app/theme";

/**
 * The prompts the models are actually given. Architecture §10.4, §19.2.
 *
 * <p>An administrator could already append guidance to the comparison prompt without ever seeing what
 * it attaches to — so there was no way to know what the model is already told, or whether new guidance
 * would contradict it. This makes that surface legible.
 *
 * <p><strong>Read-only, deliberately.</strong> These carry the output contract the validator enforces:
 * span IDs only, a bounded status set, the anti-injection nonce. Editing them does not shade results
 * slightly — every requirement fails validation and the reviewer sees "could not be evaluated" with
 * nothing pointing at the prompt. The guidance field on the AI & matching tab is the editable surface,
 * and it is appended BELOW this text with this text winning on conflict.
 */

interface PromptView {
  key: string;
  name: string;
  version: string;
  purpose: string;
  systemPrompt: string;
  guidanceKey: string | null;
}

export function PromptsPanel() {
  const prompts = useQuery({
    queryKey: ["admin-prompts"],
    queryFn: () => apiClient.get<PromptView[]>("/admin/prompts"),
  });

  if (prompts.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", p: 4 }}>
        <CircularProgress />
      </Stack>
    );
  }

  const rows = prompts.data ?? [];

  // Empty means the worker was unreachable, not that there are no prompts. Saying so beats an empty
  // panel that reads as "this feature does nothing".
  if (prompts.isError || rows.length === 0) {
    return (
      <Alert severity="info">
        Prompt templates could not be loaded. The retrieval service may be restarting — this panel is
        informational only and nothing else is affected.
      </Alert>
    );
  }

  return (
    <Stack spacing={3}>
      <Alert severity="info" icon={<LockOutlinedIcon fontSize="small" />}>
        These are shown as they are sent, and are not editable. They carry the output rules the system
        checks every model response against — a change here would make responses fail validation
        rather than simply read differently. To steer the model, use{" "}
        <strong>Comparison instructions</strong> on the AI &amp; matching tab: it is appended below
        these rules, and these rules win where the two disagree.
      </Alert>

      {rows.map((prompt) => (
        <Box
          key={prompt.key}
          sx={{ border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "#fff" }}
        >
          <Stack
            direction="row"
            spacing={2}
            sx={{
              alignItems: "center",
              p: 2,
              borderBottom: 1,
              borderColor: "divider",
              bgcolor: palette.chrome,
            }}
          >
            <Box sx={{ flex: 1 }}>
              <Typography sx={{ fontSize: 15, fontWeight: 600 }}>{prompt.name}</Typography>
              <Typography sx={{ fontSize: 13, color: palette.muted }}>{prompt.purpose}</Typography>
            </Box>
            {/* The version is the point of showing this: a stored comparison records it, so a
                finding from months ago can be traced to the exact wording that produced it (§19.2). */}
            <Chip size="small" variant="outlined" label={prompt.version} />
          </Stack>

          <Box sx={{ px: 2, pt: 1.5 }}>
            <Typography sx={overlineLabel}>System prompt</Typography>
          </Box>
          {/* Monospace and pre-wrapped: the delimiters and indentation are load-bearing, and
              reflowing them would misrepresent what the model receives. */}
          <Box
            component="pre"
            sx={{
              m: 0,
              p: 2,
              pt: 1,
              fontSize: 12,
              lineHeight: 1.6,
              fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
              color: palette.ink,
              maxHeight: 420,
              overflow: "auto",
            }}
          >
            {prompt.systemPrompt}
          </Box>

          {prompt.guidanceKey && (
            <Box sx={{ px: 2, pb: 2 }}>
              <Typography sx={{ fontSize: 12, color: palette.muted }}>
                Your guidance from the AI &amp; matching tab is appended below this text at request
                time, inside its own delimiter.
              </Typography>
            </Box>
          )}
        </Box>
      ))}

      {/* The nonce is worth explaining: a reader who sees a literal placeholder may assume the
          prompt is incomplete or that a value failed to substitute. */}
      <Typography sx={{ fontSize: 12, color: palette.muted }}>
        <code>&lt;nonce&gt;</code> stands for a random value generated per request. It delimits
        document text so that content inside a document cannot close the delimiter and have the rest
        read as instructions. No document text is shown here.
      </Typography>
    </Stack>
  );
}
