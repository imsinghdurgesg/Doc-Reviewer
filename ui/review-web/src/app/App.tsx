import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router-dom";
import { CssBaseline, ThemeProvider } from "@mui/material";
import { ErrorBoundary } from "@/components/error-boundary/ErrorBoundary";
import { AuthGate } from "@/auth/AuthGate";
import { queryClient } from "@/app/queryClient";
import { router } from "@/app/router";
import { theme } from "@/app/theme";

/** Application shell. Architecture §7.3. */
export function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <QueryClientProvider client={queryClient}>
          {/* Inside the query provider so the gate's own fetches share its error handling, and
              outside the router so an unauthenticated visitor never mounts a route. */}
          <AuthGate>
            <RouterProvider router={router} />
          </AuthGate>
        </QueryClientProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}
