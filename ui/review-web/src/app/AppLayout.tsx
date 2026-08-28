import {
  AppBar,
  Avatar,
  Box,
  Button,
  Divider,
  Menu,
  MenuItem,
  Stack,
  Toolbar,
  Typography,
} from "@mui/material";
import ShieldOutlinedIcon from "@mui/icons-material/ShieldOutlined";
import { useState } from "react";
import { Link as RouterLink, Outlet, useLocation } from "react-router-dom";
import { palette } from "@/app/theme";
import { useCurrentUser } from "@/auth/AuthGate";

/**
 * Application shell. Architecture §7.3.
 *
 * A fixed header over a flexible outlet. The outlet is `minHeight: 0` inside a flex column
 * deliberately: the review workspace is a full-height three-pane layout, and without it the flex
 * child refuses to shrink and the page grows a second scrollbar instead of the panes scrolling.
 */
export function AppLayout() {
  const { pathname } = useLocation();
  const { user, signOut } = useCurrentUser();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);

  /** Initials for the avatar, from a display name or an email local-part. */
  const initials = (user?.displayName ?? user?.email ?? "")
    .split(/[\s.@]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");

  return (
    <Stack sx={{ height: "100vh" }}>
      <AppBar position="static" color="default" elevation={0} sx={{ borderBottom: 1, borderColor: "divider" }}>
        <Toolbar variant="dense">
          {/* Brand mark with the reference implementation's asymmetric corner. */}
          <Stack direction="row" spacing={1.25} sx={{ alignItems: "center", mr: 3 }}>
            <Box
              sx={{
                display: "grid",
                placeItems: "center",
                width: 28,
                height: 28,
                color: "#fff",
                bgcolor: palette.forest,
                borderRadius: "7px 7px 7px 2px",
              }}
            >
              <ShieldOutlinedIcon sx={{ fontSize: 17 }} />
            </Box>
            <Typography variant="subtitle1" sx={{ letterSpacing: "-0.45px" }}>
              Document Review
            </Typography>
          </Stack>
          <Stack direction="row" spacing={1}>
            <Button
              component={RouterLink}
              to="/"
              size="small"
              // Exact match: every route is nested under "/", so a prefix test would mark this
              // active on every page.
              color={pathname === "/" ? "primary" : "inherit"}
            >
              Review
            </Button>
            <Button
              component={RouterLink}
              to="/history"
              size="small"
              color={pathname.startsWith("/history") ? "primary" : "inherit"}
            >
              History
            </Button>
            {/* One Admin entry rather than a Standards link: the standards library is one of three
                tabs inside Admin, and a nav item pointing at a tab makes the other two unreachable
                by name. Kept at /standards so existing links still resolve. */}
            <Button
              component={RouterLink}
              to="/standards"
              size="small"
              color={pathname.startsWith("/standards") ? "primary" : "inherit"}
            >
              Admin
            </Button>
          </Stack>

          <Box sx={{ flex: 1 }} />

          {/* Only rendered when there is an identity to show. Demo mode has none, and an avatar
              reading "D" for "demo" would imply a signed-in person who does not exist. */}
          {user && (
            <>
              <Button
                onClick={(event) => setMenuAnchor(event.currentTarget)}
                size="small"
                color="inherit"
                sx={{ textTransform: "none", pl: 0.5 }}
                aria-label="Account"
              >
                <Avatar
                  sx={{
                    width: 26,
                    height: 26,
                    fontSize: 11,
                    fontWeight: 700,
                    bgcolor: palette.mint,
                    color: palette.forest,
                    mr: 1,
                  }}
                >
                  {initials}
                </Avatar>
                <Typography variant="body2" sx={{ color: palette.muted }}>
                  {user.displayName}
                </Typography>
              </Button>
              <Menu
                anchorEl={menuAnchor}
                open={Boolean(menuAnchor)}
                onClose={() => setMenuAnchor(null)}
                anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
                transformOrigin={{ vertical: "top", horizontal: "right" }}
              >
                <Box sx={{ px: 2, py: 1 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {user.displayName}
                  </Typography>
                  <Typography variant="caption" sx={{ color: palette.muted }}>
                    {user.email}
                  </Typography>
                  {/* Roles are shown because they determine what the reviewer can do, and a missing
                      admin role otherwise surfaces only as a button that is not there. */}
                  <Typography
                    variant="caption"
                    sx={{ display: "block", mt: 0.5, color: palette.muted }}
                  >
                    {user.roles.join(" · ")}
                  </Typography>
                </Box>
                <Divider />
                <MenuItem
                  onClick={() => {
                    setMenuAnchor(null);
                    void signOut();
                  }}
                >
                  Sign out
                </MenuItem>
              </Menu>
            </>
          )}
        </Toolbar>
      </AppBar>

      <Box sx={{ flex: 1, minHeight: 0, overflow: "auto" }}>
        <Outlet />
      </Box>
    </Stack>
  );
}
