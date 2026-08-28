import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link as RouterLink } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Link,
  Stack,
  InputAdornment,
  MenuItem,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import SearchIcon from "@mui/icons-material/Search";
import RestartAltIcon from "@mui/icons-material/RestartAlt";
import {
  DEFAULT_FILTERS,
  daysFor,
  isDefault,
  type HistoryFilters,
} from "@/features/history/historyFilters";
import { PageControls } from "@/components/pagination/PageControls";
import { useDebounced } from "@/hooks/useDebounced";
import { toQuery, type PageResponse } from "@/models/page";
import { overlineLabel, palette } from "@/app/theme";
import { apiClient } from "@/api/client";
import type { ComparisonSummary } from "@/models/comparison";

/**
 * Completed and running comparisons. Architecture §19.2.
 *
 * Reopening a row shows the SAVED result — it never re-runs the model or substitutes a newer standard
 * version (§19.2). That is what makes a comparison citable weeks later.
 */
export function HistoryPage() {
  const [filters, setFilters] = useState<HistoryFilters>(DEFAULT_FILTERS);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  // The field updates on every keystroke; the QUERY waits for typing to stop. Without this,
  // "expense" issues seven requests and the results can land out of order.
  const debouncedSearch = useDebounced(filters.search, 300);

  // Filters are part of the query key, so react-query caches per filter combination and a back-and-
  // forth between two filters does not refetch.
  const comparisons = useQuery({
    // The debounced term is in the key, not the raw one, so a keystroke does not create a cache
    // entry per prefix.
    queryKey: [
      "comparisons",
      debouncedSearch,
      filters.status,
      filters.date,
      filters.reference,
      filters.sort,
      page,
      size,
    ],
    queryFn: () =>
      apiClient.get<PageResponse<ComparisonSummary>>(
        `/comparisons/search${toQuery({
          search: debouncedSearch,
          status: filters.status,
          reference: filters.reference === "ALL" ? "" : filters.reference,
          days: daysFor(filters.date),
          sort: filters.sort,
          page,
          size,
        })}`,
      ),
    // Keeps the previous page visible while the next loads, so the table does not collapse to a
    // spinner on every keystroke.
    placeholderData: (previous) => previous,
    // Overrides the client's 60s default. That default suits an immutable comparison (§19.2), but a
    // FILTERED LIST is a question about current state — with it, returning to a filter you used a
    // moment ago answered from cache and issued no request at all, which reads as the filter not
    // working.
    staleTime: 0,
  });

  /**
   * Standard options, loaded once from the unfiltered list.
   *
   * Derived from the current page would offer only standards on that page, so filtering to one of
   * them and paging away would empty the dropdown.
   */
  const references = useQuery({
    queryKey: ["comparison-standards"],
    queryFn: () => apiClient.get<{ standardVersionId: string; title: string }[]>(
      "/comparisons/standards"),
  });

  if (comparisons.isLoading) {
    return (
      <Stack sx={{ alignItems: "center", p: 4 }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (comparisons.isError) {
    return (
      <Box sx={{ p: 3 }}>
        <Alert severity="error">Could not load comparison history.</Alert>
      </Box>
    );
  }

  const result = comparisons.data;
  const rows = result?.content ?? [];
  const referenceOpts = references.data ?? [];
  // "Any reviews at all" versus "none match" — an empty first page under default filters means the
  // deployment has no history, which is a different message from a filter that matched nothing.
  const hasAny = (result?.totalElements ?? 0) > 0 || !isDefault(filters);

  // Any filter change returns to the first page: staying on page 4 of a narrowed result set shows an
  // empty table that reads as "no matches".
  const update = (patch: Partial<HistoryFilters>) => {
    setFilters((current) => ({ ...current, ...patch }));
    setPage(0);
  };

  return (
    <Box sx={{ maxWidth: 1100, mx: "auto", p: 3 }}>
      <Typography sx={{ fontSize: 27, fontWeight: 700, letterSpacing: "-0.7px", mb: 0.75 }}>
        History
      </Typography>

      <Typography sx={{ fontSize: 14, color: palette.muted, mb: 3 }}>
        Reopen previous reviews and continue from the saved findings.
      </Typography>

      {hasAny && (
        <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "#fff", mb: 3 }}>
          <Stack
            direction="row"
            spacing={2}
            sx={{ alignItems: "center", p: 2, borderBottom: 1, borderColor: "divider" }}
          >
            <TextField
              size="small"
              fullWidth
              placeholder="Search file, reviewer, reference, or comparison ID"
              value={filters.search}
              onChange={(event) => update({ search: event.target.value })}
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
            {/* Both numbers, always. "5 jobs" alone cannot be told apart from a filter that
                accidentally hid everything else. */}
            <Typography sx={{ ...overlineLabel, whiteSpace: "nowrap" }}>
              {result?.exactCount ? `${result.totalElements} reviews` : `${rows.length} on this page`}
            </Typography>
          </Stack>

          <Stack
            direction="row"
            spacing={2}
            sx={{ p: 2, flexWrap: "wrap", rowGap: 2, alignItems: "flex-end" }}
          >
            <TextField
              select size="small" label="Review status" sx={{ minWidth: 170 }}
              value={filters.status}
              onChange={(event) => update({ status: event.target.value as HistoryFilters["status"] })}
            >
              <MenuItem value="ALL">All statuses</MenuItem>
              <MenuItem value="MISMATCHES">Mismatches present</MenuItem>
              <MenuItem value="CLEAN">No mismatches</MenuItem>
              <MenuItem value="RUNNING">Still running</MenuItem>
              <MenuItem value="FAILED">Failed</MenuItem>
            </TextField>

            <TextField
              select size="small" label="Started" sx={{ minWidth: 150 }}
              value={filters.date}
              onChange={(event) => update({ date: event.target.value as HistoryFilters["date"] })}
            >
              <MenuItem value="ALL">All time</MenuItem>
              <MenuItem value="TODAY">Last 24 hours</MenuItem>
              <MenuItem value="WEEK">Last 7 days</MenuItem>
              <MenuItem value="MONTH">Last 30 days</MenuItem>
            </TextField>

            <TextField
              select size="small" label="Standard" sx={{ minWidth: 220 }}
              value={filters.reference}
              onChange={(event) => update({ reference: event.target.value })}
            >
              <MenuItem value="ALL">All standards</MenuItem>
              {referenceOpts.map((option) => (
                <MenuItem key={option.standardVersionId} value={option.standardVersionId}>
                  {option.title}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select size="small" label="Sort by" sx={{ minWidth: 170 }}
              value={filters.sort}
              onChange={(event) => update({ sort: event.target.value as HistoryFilters["sort"] })}
            >
              <MenuItem value="NEWEST">Newest first</MenuItem>
              <MenuItem value="OLDEST">Oldest first</MenuItem>
              <MenuItem value="MOST_FINDINGS">Most findings</MenuItem>
            </TextField>

            <Button
              size="small"
              startIcon={<RestartAltIcon />}
              disabled={isDefault(filters)}
              onClick={() => setFilters(DEFAULT_FILTERS)}
            >
              Reset
            </Button>
          </Stack>
        </Box>
      )}

      {!hasAny && !comparisons.isLoading && (
        <Alert severity="info">No reviews yet. Upload a document to start one.</Alert>
      )}

      {hasAny && rows.length === 0 && !comparisons.isLoading && (
        <Alert severity="info">No reviews match these filters.</Alert>
      )}

      {rows.length > 0 && (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={overlineLabel}>Document</TableCell>
              <TableCell sx={overlineLabel}>Top reference</TableCell>
              <TableCell sx={overlineLabel}>Status</TableCell>
              <TableCell align="right" sx={overlineLabel}>Findings</TableCell>
              <TableCell sx={overlineLabel}>Reviewer</TableCell>
              <TableCell sx={overlineLabel}>Started</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow
                key={row.publicId}
                hover
                sx={{ "&:hover": { bgcolor: palette.rowHover } }}
              >
                <TableCell>
                  <Link
                    component={RouterLink}
                    to={`/comparisons/${row.publicId}`}
                    sx={{ fontWeight: 600, textDecoration: "none" }}
                  >
                    {row.filename}
                  </Link>
                  <Typography sx={{ fontSize: 11, color: palette.muted, mt: 0.25 }}>
                    {row.publicId}
                  </Typography>
                </TableCell>
                <TableCell>
                  {row.topStandardTitle ? (
                    <>
                      <Typography sx={{ fontSize: 13, fontWeight: 600 }}>
                        {row.topStandardTitle}
                      </Typography>
                      {row.topStandardKey && (
                        <Typography sx={{ fontSize: 11, color: palette.muted }}>
                          {row.topStandardKey}
                        </Typography>
                      )}
                    </>
                  ) : (
                    // A failed review resolved no standard. Saying so beats an empty cell that
                    // reads as missing data.
                    <Typography sx={{ fontSize: 12, color: palette.muted }}>—</Typography>
                  )}
                </TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    variant="outlined"
                    label={row.status.replaceAll("_", " ").toLowerCase()}
                    color={colorFor(row.status)}
                  />
                </TableCell>
                <TableCell align="right">
                  {/* Actionable count, not total. Aligned controls are retained (§18.6) but are not
                      work items, so leading with the total would overstate the workload. */}
                  {row.actionableFindingCount}
                  {row.findingCount !== row.actionableFindingCount && (
                    <Typography component="span" variant="caption" color="text.secondary">
                      {" "}
                      / {row.findingCount}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>
                  {/* The full address, not the local part. It is the identity the authorization
                      decision was made against, and a compliance record should name it exactly as
                      stored rather than in an abbreviated form a reader has to reconstruct. */}
                  {row.reviewer ? (
                    <Typography sx={{ fontSize: 13, whiteSpace: "nowrap" }}>
                      {row.reviewer}
                    </Typography>
                  ) : (
                    // Stored before the field was returned. A dash says "not recorded" rather than
                    // implying nobody ran it.
                    <Typography sx={{ fontSize: 12, color: palette.muted }}>—</Typography>
                  )}
                </TableCell>
                <TableCell>
                  <Typography variant="caption" color="text.secondary">
                    {new Date(row.createdAt).toLocaleString()}
                  </Typography>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {result && rows.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <PageControls
            page={result}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
            label="reviews"
          />
        </Box>
      )}
    </Box>
  );
}

/**
 * Maps a state to a chip colour.
 *
 * PARTIALLY_COMPLETED is warning, not success: §18.4 makes it a real outcome where some requirements
 * were never evaluated, and colouring it green would hide exactly the gap the state exists to show.
 */
function colorFor(status: string): "success" | "warning" | "error" | "default" {
  switch (status) {
    case "COMPLETED":
      return "success";
    case "PARTIALLY_COMPLETED":
      return "warning";
    case "FAILED":
      return "error";
    default:
      return "default";
  }
}
