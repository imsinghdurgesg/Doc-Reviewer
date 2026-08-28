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
  FormControlLabel,
  InputAdornment,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import SearchIcon from "@mui/icons-material/Search";
import { ApiError, apiClient } from "@/api/client";
import { PageControls } from "@/components/pagination/PageControls";
import { useDebounced } from "@/hooks/useDebounced";
import { toQuery, type PageResponse } from "@/models/page";
import { overlineLabel, palette } from "@/app/theme";

/**
 * Who may sign in, and what they may do. Architecture §33.
 *
 * <p>Access is invite-only: a Stanford account proves identity, this list decides authorisation. So
 * removing someone here removes their access, which is why the destructive controls are guarded rather
 * than merely confirmed — see the disabled states below.
 */

interface AppUser {
  email: string;
  displayName: string | null;
  roles: string[];
  status: "ACTIVE" | "DISABLED";
  lastLoginAt: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  /** True for the signed-in administrator's own row. */
  self: boolean;
}

/** REVIEWER is implicit on every user, so it is not offered as a choice. */
const ASSIGNABLE = ["STANDARDS_ADMIN", "ADMIN"] as const;

export function UsersPanel() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRoles, setInviteRoles] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const debouncedSearch = useDebounced(search, 300);

  const users = useQuery({
    queryKey: ["admin-users", debouncedSearch, page, size],
    queryFn: () =>
      apiClient.get<PageResponse<AppUser>>(
        `/admin/users${toQuery({ search: debouncedSearch, page, size })}`,
      ),
    placeholderData: (previous) => previous,
    // A list of who has access is a question about current state, so it is not cached.
    staleTime: 0,
  });

  const refresh = () => {
    setError(null);
    void queryClient.invalidateQueries({ queryKey: ["admin-users"] });
  };
  const fail = (e: unknown) =>
    setError(e instanceof ApiError ? e.message : "The change could not be saved.");

  const invite = useMutation({
    mutationFn: () =>
      apiClient.post<AppUser>("/admin/users", { email: inviteEmail, roles: inviteRoles }),
    onSuccess: () => {
      setInviteOpen(false);
      setInviteEmail("");
      setInviteRoles([]);
      refresh();
    },
    onError: fail,
  });

  const setRoles = useMutation({
    mutationFn: ({ email, roles }: { email: string; roles: string[] }) =>
      apiClient.put<AppUser>(`/admin/users/${encodeURIComponent(email)}/roles`, { roles }),
    onSuccess: refresh,
    onError: fail,
  });

  const setStatus = useMutation({
    mutationFn: ({ email, status }: { email: string; status: string }) =>
      apiClient.put<AppUser>(`/admin/users/${encodeURIComponent(email)}/status`, { status }),
    onSuccess: refresh,
    onError: fail,
  });

  if (users.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", p: 4 }}>
        <CircularProgress />
      </Stack>
    );
  }

  const result = users.data;
  const rows = result?.content ?? [];

  const toggleRole = (user: AppUser, role: string) => {
    const next = user.roles.includes(role)
      ? user.roles.filter((r) => r !== role)
      : [...user.roles, role];
    setRoles.mutate({ email: user.email, roles: next.filter((r) => r !== "REVIEWER") });
  };

  return (
    <Box sx={{ bgcolor: "#fff", border: 1, borderColor: "divider", borderRadius: 1 }}>
      <Stack
        direction="row"
        spacing={2}
        sx={{ alignItems: "center", p: 3, borderBottom: 1, borderColor: "divider" }}
      >
        <Box sx={{ flex: 1 }}>
          <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: "-0.4px" }}>
            Users
          </Typography>
          <Typography sx={{ fontSize: 13, color: palette.muted, mt: 0.5 }}>
            Only the people listed here can sign in. A Stanford account alone is not enough.
          </Typography>
        </Box>
        <Button variant="contained" onClick={() => setInviteOpen(true)}>
          Add user
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ m: 3, mb: 0 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Stack direction="row" spacing={2} sx={{ p: 3, alignItems: "center" }}>
        <TextField
          size="small"
          fullWidth
          placeholder="Search by email or name"
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
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
        <Typography sx={{ ...overlineLabel, whiteSpace: "nowrap" }}>
          {result?.totalElements ?? 0} users
        </Typography>
      </Stack>

      <Box sx={{ px: 3, pb: 1 }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={overlineLabel}>User</TableCell>
              <TableCell sx={overlineLabel}>Roles</TableCell>
              <TableCell sx={overlineLabel}>Last sign-in</TableCell>
              <TableCell sx={overlineLabel}>Access</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((user) => (
              <TableRow key={user.email} hover>
                <TableCell>
                  <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                    {user.email}
                    {user.self && (
                      <Chip size="small" label="you" sx={{ ml: 1, height: 18, fontSize: 10 }} />
                    )}
                  </Typography>
                  {user.displayName && user.displayName !== user.email && (
                    <Typography sx={{ fontSize: 11, color: palette.muted }}>
                      {user.displayName}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>
                  {/* REVIEWER is shown but not removable: every listed user has it, and offering it
                      as a toggle would imply a user can exist with no role at all. */}
                  <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", rowGap: 0.5 }}>
                    <Chip size="small" variant="outlined" label="REVIEWER"
                      sx={{ height: 22, fontSize: 11, color: palette.muted }} />
                    {ASSIGNABLE.map((role) => {
                      const held = user.roles.includes(role);
                      // Removing your own ADMIN is the one change with no in-app recovery, so the
                      // control is disabled rather than left to fail server-side.
                      const locked = user.self && role === "ADMIN" && held;
                      return (
                        <Tooltip
                          key={role}
                          title={locked
                            ? "You cannot remove your own administrator role"
                            : held ? "Click to remove" : "Click to grant"}
                        >
                          <Chip
                            size="small"
                            label={role}
                            variant={held ? "filled" : "outlined"}
                            color={held ? (role === "ADMIN" ? "primary" : "default") : "default"}
                            onClick={locked ? undefined : () => toggleRole(user, role)}
                            disabled={locked || setRoles.isPending}
                            sx={{ height: 22, fontSize: 11, cursor: locked ? "default" : "pointer" }}
                          />
                        </Tooltip>
                      );
                    })}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary">
                    {/* "Never" distinguishes an invited user from an active one — otherwise a blank
                        cell reads as missing data rather than "has not signed in yet". */}
                    {user.lastLoginAt
                      ? new Date(user.lastLoginAt).toLocaleString()
                      : "Never"}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Tooltip
                    title={user.self
                      ? "You cannot disable your own account"
                      : user.status === "ACTIVE" ? "Revoke access" : "Restore access"}
                  >
                    <FormControlLabel
                      control={
                        <Switch
                          size="small"
                          checked={user.status === "ACTIVE"}
                          disabled={user.self || setStatus.isPending}
                          onChange={(event) =>
                            setStatus.mutate({
                              email: user.email,
                              status: event.target.checked ? "ACTIVE" : "DISABLED",
                            })
                          }
                        />
                      }
                      label={
                        <Typography sx={{ fontSize: 12 }}>
                          {user.status === "ACTIVE" ? "Active" : "Disabled"}
                        </Typography>
                      }
                    />
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>

      {result && (
        <Box sx={{ px: 3, pb: 3 }}>
          <PageControls
            page={result}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </Box>
      )}

      <Dialog open={inviteOpen} onClose={() => setInviteOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Add user</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography sx={{ fontSize: 13, color: palette.muted }}>
              They can be added before they first sign in. Everyone gets the reviewer role; add others
              only if they need them.
            </Typography>
            <TextField
              label="Email"
              required
              fullWidth
              size="small"
              value={inviteEmail}
              onChange={(event) => setInviteEmail(event.target.value)}
              placeholder="someone@stanford.edu"
            />
            <Stack direction="row" spacing={1}>
              {ASSIGNABLE.map((role) => (
                <Chip
                  key={role}
                  label={role}
                  variant={inviteRoles.includes(role) ? "filled" : "outlined"}
                  color={inviteRoles.includes(role) ? "primary" : "default"}
                  onClick={() =>
                    setInviteRoles((current) =>
                      current.includes(role)
                        ? current.filter((r) => r !== role)
                        : [...current, role],
                    )
                  }
                />
              ))}
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setInviteOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!inviteEmail.includes("@") || invite.isPending}
            onClick={() => invite.mutate()}
          >
            Add
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
