import {
  Box,
  Divider,
  IconButton,
  Stack,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from "@mui/material";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import ZoomOutIcon from "@mui/icons-material/ZoomOut";
import FitScreenIcon from "@mui/icons-material/FitScreen";
import FullscreenIcon from "@mui/icons-material/Fullscreen";
import FullscreenExitIcon from "@mui/icons-material/FullscreenExit";
import LinkIcon from "@mui/icons-material/Link";
import LinkOffIcon from "@mui/icons-material/LinkOff";

/** Which pane a narrow viewport is showing, since two side by side do not fit. */
export type MobilePane = "process" | "standard";

export interface ViewerControlBarProps {
  zoom: number;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitWidth: () => void;
  scrollTogether: boolean;
  onScrollTogetherChange: (value: boolean) => void;
  fullscreen: boolean;
  onToggleFullscreen: () => void;
  mobilePane: MobilePane;
  onMobilePaneChange: (pane: MobilePane) => void;
}

/**
 * Controls for the two document panes. Architecture §19.1.
 *
 * <p>The panes render parsed text rather than page images. Evidence is located by character offset
 * inside a canonical node, so these controls act on reflowed content: zoom changes the type scale
 * rather than magnifying a canvas, and fit-width sizes the measure rather than a page.
 */
export function ViewerControlBar({
  zoom,
  onZoomIn,
  onZoomOut,
  onFitWidth,
  scrollTogether,
  onScrollTogetherChange,
  fullscreen,
  onToggleFullscreen,
  mobilePane,
  onMobilePaneChange,
}: ViewerControlBarProps) {
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{
        alignItems: "center",
        px: 1.5,
        py: 0.75,
        borderBottom: 1,
        borderColor: "divider",
        flexWrap: "wrap",
        rowGap: 0.5,
      }}
    >
      <Tooltip title="Zoom out">
        <span>
          <IconButton size="small" onClick={onZoomOut} aria-label="Zoom out">
            <ZoomOutIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Typography variant="caption" sx={{ minWidth: 42, textAlign: "center" }}>
        {zoom}%
      </Typography>
      <Tooltip title="Zoom in">
        <span>
          <IconButton size="small" onClick={onZoomIn} aria-label="Zoom in">
            <ZoomInIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
      <Tooltip title="Fit to width">
        <IconButton size="small" onClick={onFitWidth} aria-label="Fit to width">
          <FitScreenIcon fontSize="small" />
        </IconButton>
      </Tooltip>

      <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />

      <Tooltip
        title={scrollTogether ? "Turn off synchronized scrolling" : "Scroll both documents together"}
      >
        <IconButton
          size="small"
          onClick={() => onScrollTogetherChange(!scrollTogether)}
          aria-label="Scroll together"
          aria-pressed={scrollTogether}
          color={scrollTogether ? "primary" : "default"}
        >
          {scrollTogether ? <LinkIcon fontSize="small" /> : <LinkOffIcon fontSize="small" />}
        </IconButton>
      </Tooltip>

      <Tooltip title={fullscreen ? "Exit full screen" : "Full screen"}>
        <IconButton size="small" onClick={onToggleFullscreen} aria-label="Toggle full screen">
          {fullscreen ? <FullscreenExitIcon fontSize="small" /> : <FullscreenIcon fontSize="small" />}
        </IconButton>
      </Tooltip>

      <Box sx={{ flex: 1 }} />

      {/* Narrow viewports show one pane at a time; side by side below roughly 900px makes both
          unreadable rather than compact. */}
      <ToggleButtonGroup
        size="small"
        exclusive
        value={mobilePane}
        onChange={(_, next: MobilePane | null) => next && onMobilePaneChange(next)}
        aria-label="Visible document"
        sx={{ display: { xs: "flex", md: "none" } }}
      >
        <ToggleButton value="process">Process</ToggleButton>
        <ToggleButton value="standard">Standard</ToggleButton>
      </ToggleButtonGroup>
    </Stack>
  );
}
