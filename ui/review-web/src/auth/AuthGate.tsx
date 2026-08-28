import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { CircularProgress, Stack } from "@mui/material";
import { SignInScreen } from "@/auth/SignInScreen";
import {
  fetchAuthConfig,
  fetchSession,
  signOut,
  type AuthConfig,
  type SessionState,
  type SessionUser,
} from "@/auth/session";

/**
 * Gates the application behind authentication. Architecture §33.
 *
 * <p>Renders the sign-in screen when the server is in SSO mode and nobody is signed in, and the
 * application otherwise. Demo mode falls straight through: HTTP Basic has already authenticated the
 * request by the time any JavaScript runs, so there is nothing to sign into.
 *
 * <p>The server DECLARES whether SSO is on, at `/api/auth/config`. Turning it off is a supported
 * configuration — `SECURITY_MODE=demo` — and the SPA then renders the application with no login
 * screen at all. A build-time flag would have to stay in step with that server-side property, and
 * the failure when it drifts is a sign-in screen that cannot work.
 */

interface AuthContextValue {
  user: SessionUser | null;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue>({
  user: null,
  signOut: async () => {},
});

/** The signed-in user, or null in demo mode where there is no identity to speak of. */
export function useCurrentUser(): AuthContextValue {
  return useContext(AuthContext);
}

export function AuthGate({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionState>({ status: "loading" });
  const [config, setConfig] = useState<AuthConfig>({
    ssoEnabled: false,
    clientId: null,
    hostedDomain: null,
  });

  useEffect(() => {
    let cancelled = false;

    // Config first, then session: whether to ask about a session at all depends on the answer.
    fetchAuthConfig()
      .then((authConfig) => {
        if (cancelled) {
          return null;
        }
        setConfig(authConfig);
        return fetchSession(authConfig);
      })
      .then((next) => {
        if (!cancelled && next) {
          setSession(next);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const handleSignedIn = useCallback((user: SessionUser) => {
    setSession({ status: "authenticated", user });
  }, []);

  const handleSignOut = useCallback(async () => {
    await signOut();
    setSession({ status: "anonymous" });
  }, []);

  if (session.status === "loading") {
    // A spinner, not the sign-in screen: flashing a sign-in prompt at an already-authenticated user
    // reads as having been logged out.
    return (
      <Stack sx={{ height: "100vh", alignItems: "center", justifyContent: "center" }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (session.status === "anonymous") {
    return (
      <SignInScreen
        clientId={config.clientId}
        hostedDomain={config.hostedDomain}
        onSignedIn={handleSignedIn}
      />
    );
  }

  return (
    <AuthContext.Provider value={{ user: session.user, signOut: handleSignOut }}>
      {children}
    </AuthContext.Provider>
  );
}
