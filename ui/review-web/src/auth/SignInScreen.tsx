import { useEffect, useRef, useState } from "react";
import { Alert, Box, CircularProgress, Stack, Typography } from "@mui/material";
import ShieldOutlinedIcon from "@mui/icons-material/ShieldOutlined";
import { palette } from "@/app/theme";
import { SignInError, signInWithGoogle, type SessionUser } from "@/auth/session";

/**
 * Google sign-in. Architecture §33.
 *
 * <p>Renders Google Identity Services' own button rather than a look-alike. That is a requirement,
 * not a style choice: Google's terms require the official button for the popup flow, and it is also
 * what users recognise as genuine — a hand-built "Sign in with Google" control is exactly what a
 * phishing page shows.
 *
 * <p>The script is loaded on demand rather than in {@code index.html}. Demo mode never renders this
 * screen, so a global script tag would fetch and execute third-party code on every local run that
 * has no identity provider at all.
 */

/** The slice of the GIS API this component uses. */
interface GoogleIdentityServices {
  accounts: {
    id: {
      initialize(config: {
        client_id: string;
        callback: (response: { credential?: string }) => void;
        auto_select?: boolean;
        cancel_on_tap_outside?: boolean;
      }): void;
      renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
    };
  };
}

declare global {
  interface Window {
    google?: GoogleIdentityServices;
  }
}

const GIS_SCRIPT = "https://accounts.google.com/gsi/client";

/** Loads the GIS script once, reusing the in-flight promise for concurrent callers. */
let scriptPromise: Promise<void> | null = null;

function loadGoogleScript(): Promise<void> {
  if (scriptPromise) {
    return scriptPromise;
  }
  scriptPromise = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SCRIPT}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("script failed")));
      return;
    }
    const script = document.createElement("script");
    script.src = GIS_SCRIPT;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => {
      // Reset so a retry can attempt the load again rather than reusing a rejected promise.
      scriptPromise = null;
      reject(new Error("script failed"));
    };
    document.head.append(script);
  });
  return scriptPromise;
}

export interface SignInScreenProps {
  /** OAuth client id, served by the backend so it is not baked into the bundle at build time. */
  clientId: string | null;
  hostedDomain: string | null;
  onSignedIn: (user: SessionUser) => void;
}

export function SignInScreen({ clientId, hostedDomain, onSignedIn }: SignInScreenProps) {
  const buttonRef = useRef<HTMLDivElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [scriptFailed, setScriptFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!clientId) {
      return;
    }
    let cancelled = false;

    loadGoogleScript()
      .then(() => {
        if (cancelled || !window.google || !buttonRef.current) {
          return;
        }
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => {
            if (!response.credential) {
              setError("Google returned no credential. Please try again.");
              return;
            }
            setBusy(true);
            setError(null);
            signInWithGoogle(response.credential)
              .then(onSignedIn)
              .catch((cause: unknown) => {
                setError(
                  cause instanceof SignInError
                    ? cause.message
                    : "Sign-in failed. Please try again.",
                );
              })
              .finally(() => setBusy(false));
          },
          // No automatic sign-in: this is a compliance tool, and silently resuming a session for
          // whoever is signed into the browser is the wrong default on a shared machine.
          auto_select: false,
          cancel_on_tap_outside: true,
        });
        window.google.accounts.id.renderButton(buttonRef.current, {
          theme: "outline",
          size: "large",
          shape: "rectangular",
          text: "signin_with",
          logo_alignment: "left",
          width: 280,
        });
      })
      .catch(() => {
        if (!cancelled) {
          setScriptFailed(true);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [clientId, onSignedIn]);

  return (
    <Stack
      sx={{
        minHeight: "100vh",
        alignItems: "center",
        justifyContent: "center",
        bgcolor: palette.paper,
        px: 2,
      }}
    >
      <Box
        sx={{
          width: 420,
          maxWidth: "100%",
          bgcolor: "#fff",
          border: 1,
          borderColor: palette.border,
          borderRadius: 1,
          boxShadow: "0 3px 14px rgba(34, 48, 41, .10)",
          p: 5,
        }}
      >
        {/* The brand mark, matching the reference implementation's asymmetric corner. */}
        <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", mb: 3 }}>
          <Box
            sx={{
              display: "grid",
              placeItems: "center",
              width: 34,
              height: 34,
              color: "#fff",
              bgcolor: palette.forest,
              borderRadius: "9px 9px 9px 3px",
            }}
          >
            <ShieldOutlinedIcon fontSize="small" />
          </Box>
          <Typography variant="h6" sx={{ letterSpacing: "-0.45px" }}>
            Document Review
          </Typography>
        </Stack>

        <Typography variant="body2" sx={{ color: palette.muted, mb: 4, lineHeight: 1.6 }}>
          Compare an operating procedure against an approved standard, with every finding traced to
          the passage it cites.
        </Typography>

        {!clientId && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Sign-in is not configured. Set GOOGLE_CLIENT_ID on the server.
          </Alert>
        )}

        {scriptFailed && (
          <Alert severity="error" sx={{ mb: 2 }}>
            Google sign-in could not be loaded. Check network access to accounts.google.com.
          </Alert>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {/* GIS renders its button into this node. Kept mounted even while busy so the iframe is not
            torn down and re-created on every state change. */}
        <Stack sx={{ alignItems: "center", minHeight: 44, justifyContent: "center" }}>
          {busy ? <CircularProgress size={24} /> : <div ref={buttonRef} />}
        </Stack>

        {hostedDomain && (
          <Typography
            variant="caption"
            sx={{ display: "block", mt: 3, color: palette.muted, textAlign: "center" }}
          >
            Restricted to {hostedDomain} accounts.
          </Typography>
        )}
      </Box>
    </Stack>
  );
}
