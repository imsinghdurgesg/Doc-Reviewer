import { MenuItem, Stack, TablePagination, TextField, Typography } from "@mui/material";
import { palette } from "@/app/theme";
import type { PageResponse } from "@/models/page";

/**
 * Pager for any {@link PageResponse}. Architecture §7.4.
 *
 * <p>Generic over the row type so every paginated collection uses the same control — a second,
 * subtly different pager is how two lists end up disagreeing about whether pages are zero- or
 * one-based.
 *
 * <p><strong>An inexact total is stated, not rounded over.</strong> When the server could not apply
 * a filter in the query it says so, and this renders "showing N of these results" instead of a total
 * it knows counts rows the page dropped. Presenting an approximate figure as exact is how a reviewer
 * concludes there are 40 matching reviews when there are 8.
 */
export interface PageControlsProps<T> {
  page: PageResponse<T>;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  /** Plural noun for the count line, e.g. "reviews". */
  label?: string;
}

export function PageControls<T>({
  page,
  onPageChange,
  onSizeChange,
  label = "results",
}: PageControlsProps<T>) {
  if (page.totalElements === 0 && page.content.length === 0) {
    return null;
  }

  return (
    <Stack
      direction="row"
      sx={{ alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", rowGap: 1 }}
    >
      <Typography variant="caption" sx={{ color: palette.muted }}>
        {page.exactCount
          ? `${page.totalElements} ${label}`
          : // The count cannot be trusted as a total, so only what is on screen is claimed.
            `Showing ${page.content.length} ${label} on this page`}
      </Typography>

      <TablePagination
        component="div"
        count={page.exactCount ? page.totalElements : -1}
        page={page.page}
        onPageChange={(_, next) => onPageChange(next)}
        rowsPerPage={page.size}
        onRowsPerPageChange={(event) => onSizeChange(Number(event.target.value))}
        rowsPerPageOptions={[10, 20, 50, 100]}
        // count={-1} makes MUI render "1–20 of more than 20", which is the honest rendering when the
        // total is not exact.
        labelRowsPerPage="Per page"
        sx={{
          border: 0,
          "& .MuiTablePagination-toolbar": { pl: 0, minHeight: 44 },
        }}
      />
    </Stack>
  );
}

/** Page-size selector for use outside a table, sharing the same options. */
export function PageSizeSelect({
  size,
  onChange,
}: {
  size: number;
  onChange: (size: number) => void;
}) {
  return (
    <TextField
      select
      size="small"
      label="Per page"
      value={size}
      onChange={(event) => onChange(Number(event.target.value))}
      sx={{ minWidth: 110 }}
    >
      {[10, 20, 50, 100].map((option) => (
        <MenuItem key={option} value={option}>
          {option}
        </MenuItem>
      ))}
    </TextField>
  );
}
