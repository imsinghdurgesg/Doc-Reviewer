import { Box, Stack, Typography } from "@mui/material";
import FilterAltOutlinedIcon from "@mui/icons-material/FilterAltOutlined";
import { overlineLabel, palette } from "@/app/theme";

/**
 * The reviewer's assessment focus, read-only. Architecture §19.2.
 *
 * <p><strong>Why this is shown at all.</strong> Guidance steers which parts of a standard are
 * retrieved, so it changes which findings exist. The same document against the same standard produces
 * different results under "emergency change controls only" than under no focus at all — and a reader
 * who cannot see the focus has no way to tell which review they are looking at. A completed review has
 * to stay interpretable by someone who did not run it.
 *
 * <p><strong>Read-only, deliberately.</strong> The inputs of a finished comparison are fixed. An
 * editable field here would imply the findings would re-derive to match, which they would not — the
 * only honest way to change the focus is to run a new comparison.
 *
 * <p><strong>Shown once per review, never per finding.</strong> It applies to the whole assessment, so
 * repeating it on every finding would add noise proportional to the finding count while saying nothing
 * new — and would imply, wrongly, that the focus was a property of the individual finding.
 *
 * <p>One component for all three surfaces (selection, overview, history detail) so the wording cannot
 * drift between them. `variant` controls density only.
 */
export function AssessmentFocus({
  guidance,
  variant = "block",
}: {
  guidance: string | null | undefined;
  /** `block` for a page section; `inline` for a denser panel such as the overview header. */
  variant?: "block" | "inline";
}) {
  // Absent focus renders NOTHING rather than "No focus given". A permanent empty row on every review
  // that did not use the feature is noise, and "none" is the unremarkable default.
  const text = guidance?.trim();
  if (!text) {
    return null;
  }

  return (
    <Box
      sx={{
        mt: variant === "block" ? 2 : 1,
        p: variant === "block" ? 1.75 : 1.25,
        borderRadius: 1,
        bgcolor: palette.mint,
        // A left rule rather than a full border: this is context for what follows, not a callout
        // competing with the findings for attention.
        borderLeft: `3px solid ${palette.forest}`,
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 0.5 }}>
        <FilterAltOutlinedIcon sx={{ fontSize: 14, color: palette.forest }} />
        <Typography sx={{ ...overlineLabel, fontSize: 9, color: palette.forest }}>
          Assessment focus
        </Typography>
      </Stack>
      <Typography
        sx={{
          fontSize: variant === "block" ? 13 : 12.5,
          lineHeight: 1.5,
          color: palette.ink,
          // Reviewer-authored text: newlines are meaningful, and collapsing them would run separate
          // scoping notes together into one sentence.
          whiteSpace: "pre-wrap",
        }}
      >
        {text}
      </Typography>
      <Typography sx={{ fontSize: 11, color: palette.muted, mt: 0.75 }}>
        Used to steer which parts of the standards were searched. Findings reflect this focus.
      </Typography>
    </Box>
  );
}
