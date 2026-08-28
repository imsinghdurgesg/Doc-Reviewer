import { Component, type ErrorInfo, type ReactNode } from "react";
import { Alert, AlertTitle, Box } from "@mui/material";

/**
 * Top-level error boundary. Architecture §7.3.
 *
 * Without one, a render error in any component unmounts the whole tree and leaves a blank page with
 * the cause visible only in the console. That failure mode is genuinely hard to diagnose from a user
 * report: "the page went white" describes a hundred different bugs.
 */
interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // Console only. Document text can appear in a React error message, and §34 forbids sending
    // document content anywhere it was not already going.
    console.error("Unhandled UI error", error, info.componentStack);
  }

  override render(): ReactNode {
    if (this.state.error) {
      return (
        <Box sx={{ p: 3 }}>
          <Alert severity="error">
            <AlertTitle>Something went wrong</AlertTitle>
            {this.state.error.message}
          </Alert>
        </Box>
      );
    }
    return this.props.children;
  }
}
