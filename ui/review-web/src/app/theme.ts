import { createTheme } from "@mui/material/styles";

/**
 * Application theme. Architecture §5.1.
 *
 * Light mode: this is a document-review tool, and reviewers compare rendered pages against on-screen
 * text for long stretches. A dark chrome around white document panes creates exactly the contrast
 * boundary that causes eye strain in that task.
 *
 * <h2>Palette</h2>
 *
 * A deep green rather than the default blue, matching the reference implementation. The reason is
 * functional as well as visual: blue is the platform's link and information colour, and the review
 * surface already spends red, amber and green on finding STATUS. A primary that reads as "info"
 * competes with those, so the chrome takes a colour the status vocabulary does not use.
 */

/** Shared tokens, exported so components can reach them without re-deriving hex values. */
export const palette = {
  ink: "#172720",
  muted: "#66736d",
  forest: "#173c32",
  forestLight: "#245748",
  mint: "#dbe8df",
  paper: "#fffef9",
  border: "#d9ded8",
  amber: "#d98b2b",
  red: "#be5246",
  /** Pane background behind a document page. */
  canvas: "#eceee9",
  /** Chrome surfaces: command bars and toolbars sit slightly off-white from content. */
  chrome: "#fbfcfa",
  /** Hover and selected states in a scrolling list. */
  rowHover: "#fafbf9",
  rowSelected: "#f5f8f5",
  /** Status tints. Background/foreground pairs, so the icon is legible on its own chip. */
  statusHigh: { bg: "#f7e1de", fg: "#ad493f" },
  statusMedium: { bg: "#faecd9", fg: "#b87324" },
  statusAligned: { bg: "#e0eee4", fg: "#4c8064" },
} as const;

/**
 * The small-caps label used above every value in the reference implementation.
 *
 * Repeated often enough that duplicating six properties per site drifts. Exported as a style object
 * rather than a component so it composes into an existing `sx` without adding a wrapper element.
 */
export const overlineLabel = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: "0.8px",
  color: "#849087",
  textTransform: "uppercase",
} as const;

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: palette.forest, light: palette.forestLight, contrastText: "#ffffff" },
    warning: { main: palette.amber },
    error: { main: palette.red },
    text: { primary: palette.ink, secondary: palette.muted },
    divider: palette.border,
    background: { default: "#f7f8f5", paper: "#ffffff" },
  },
  typography: {
    // Manrope for chrome; the document panes override to a serif because they are documents, not UI.
    fontFamily: '"Manrope", "Inter", system-ui, -apple-system, sans-serif',
    fontSize: 14,
    h6: { fontWeight: 700, letterSpacing: "-0.3px" },
    subtitle1: { fontWeight: 600, letterSpacing: "-0.2px" },
    button: { textTransform: "none", fontWeight: 600 },
  },
  shape: { borderRadius: 4 },
  components: {
    MuiChip: {
      styleOverrides: {
        // Status chips appear many per screen; the default height wastes vertical space in a list
        // that a reviewer scrolls constantly.
        sizeSmall: { height: 22 },
      },
    },
    MuiButton: {
      styleOverrides: {
        // Squarer than MUI's default and without the uppercase shout, matching the reference.
        root: { borderRadius: 3 },
        contained: { boxShadow: "0 7px 18px rgba(23, 60, 50, .16)" },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: { backgroundColor: "#ffffff" },
      },
    },
  },
});
