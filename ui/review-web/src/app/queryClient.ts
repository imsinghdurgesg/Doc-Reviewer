import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/api/client";

/**
 * Server-state configuration. Architecture §7 (state rules).
 *
 * Server data is owned by TanStack Query; React state holds only UI concerns such as which finding
 * is selected. Copying server data into component state is what produces two versions of the truth
 * and a stale view after a refetch.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // A comparison is immutable once complete (§19.2), so refetching on every window focus is
      // pure load for data that cannot have changed.
      refetchOnWindowFocus: false,
      staleTime: 60_000,
      retry: (failureCount, error) => {
        // Never retry a client error: a 404 or a 403 will answer identically next time, and
        // retrying a validation failure just delays showing the user what went wrong.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
    },
  },
});
