/**
 * Authentication state. Architecture §33.
 *
 * Two modes exist server-side and the SPA must work under both. Demo mode authenticates with HTTP
 * Basic, so the browser holds the credential and `/api/auth/session` does not exist — every API call
 * simply succeeds. SSO mode requires a Google sign-in before anything is reachable.
 *
 * The server DECLARES which, at `/api/auth/config`, rather than the client inferring it from status
 * codes. Inference was tried and was wrong: the demo chain rejects `/api/auth/session` with 401
 * before routing can report the endpoint absent, so 401 meant "not signed in" under SSO and "there
 * is no sign-in here" under demo — and reading it as the former put an unusable sign-in screen in
 * front of every demo user. A declared flag has no such ambiguity, and it is also the switch an
 * operator turns: `SECURITY_MODE=demo` disables the login screen outright.
 */

export type SessionState =
  /** Still asking. Render nothing decisive — a sign-in screen that flashes is worse than a spinner. */
  | { status: "loading" }
  /** SSO, nobody signed in. */
  | { status: "anonymous" }
  /** Signed in, or demo mode where the server never asks. */
  | { status: "authenticated"; user: SessionUser };

export interface SessionUser {
  email: string;
  displayName: string;
  roles: readonly string[];
}

/**
 * Whether this user may change administered settings.
 *
 * <p>Mirrors the backend's {@code hasAnyRole('STANDARDS_ADMIN','ADMIN')} on the admin controllers.
 * It exists to DISABLE controls, never to hide them: a reviewer who cannot see the screen has no way
 * to learn that a setting exists or who to ask, whereas a disabled field with a reason is
 * self-explanatory.
 *
 * <p>This is presentation only. The backend rejects the write regardless, so a user who re-enables a
 * field in devtools gains nothing — which is what makes it safe to render the form at all.
 *
 * <p>Fails CLOSED: a null user or an empty role list is treated as read-only. A session that failed
 * to load must not present itself as permitted.
 */
export function canAdminister(user: SessionUser | null): boolean {
  if (!user) {
    return false;
  }
  return user.roles.includes("ADMIN") || user.roles.includes("STANDARDS_ADMIN");
}

/** What the backend returns from /api/auth/session and /api/auth/google. */
interface SessionResponse {
  email: string;
  displayName: string;
  roles: string[];
}

/** Stand-in identity for demo mode, where HTTP Basic has already authenticated the request. */
const DEMO_USER: SessionUser = {
  email: "demo",
  displayName: "Demo user",
  roles: ["REVIEWER"],
};

/** What the server says about authentication. Public, and available before any credential exists. */
export interface AuthConfig {
  ssoEnabled: boolean;
  clientId: string | null;
  hostedDomain: string | null;
}

/** SSO off is the safe reading when the answer cannot be obtained: the app renders, nothing breaks. */
const SSO_DISABLED: AuthConfig = { ssoEnabled: false, clientId: null, hostedDomain: null };

export async function fetchAuthConfig(): Promise<AuthConfig> {
  try {
    const response = await fetch("/api/auth/config", {
      headers: { Accept: "application/json" },
      credentials: "same-origin",
    });
    if (!response.ok) {
      return SSO_DISABLED;
    }
    return (await response.json()) as AuthConfig;
  } catch {
    return SSO_DISABLED;
  }
}

/**
 * Reads the current session.
 *
 * Deliberately does NOT use the shared api client: that maps every non-2xx onto a thrown ApiError,
 * and here a 401 is an expected answer ("not signed in") rather than a failure. Throwing would make
 * the normal unauthenticated path indistinguishable from a server fault.
 */
export async function fetchSession(config: AuthConfig): Promise<SessionState> {
  // SSO off: there is nothing to sign into, and the request has already been authenticated (demo) or
  // needs no authentication at all. Asking the session endpoint would only produce a 401 to ignore.
  if (!config.ssoEnabled) {
    return { status: "authenticated", user: DEMO_USER };
  }

  let response: Response;
  try {
    response = await fetch("/api/auth/session", {
      headers: { Accept: "application/json" },
      credentials: "same-origin",
    });
  } catch {
    // Network failure. Treated as anonymous so the sign-in screen renders rather than a blank page;
    // the sign-in attempt will surface the real error.
    return { status: "anonymous" };
  }

  // SSO is on, so 401 is unambiguous: nobody is signed in.
  if (!response.ok) {
    return { status: "anonymous" };
  }

  const body = (await response.json()) as SessionResponse;
  return {
    status: "authenticated",
    user: { email: body.email, displayName: body.displayName, roles: body.roles ?? [] },
  };
}

/** Error carrying the server's own explanation, which is safe to show for a rejected sign-in. */
export class SignInError extends Error {
  constructor(
    message: string,
    /** 403 means the account is valid but not permitted — retrying will never help. */
    readonly permanent: boolean,
  ) {
    super(message);
    this.name = "SignInError";
  }
}

/**
 * Exchanges a Google ID token for a session.
 *
 * @param credential the JWT from Google Identity Services
 */
export async function signInWithGoogle(credential: string): Promise<SessionUser> {
  const response = await fetch("/api/auth/google", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ credential }),
  });

  if (!response.ok) {
    let message = "Sign-in failed.";
    try {
      const body = (await response.json()) as { message?: string };
      if (body.message) {
        message = body.message;
      }
    } catch {
      // Non-JSON body (a proxy error page). The default message stands.
    }
    // 403 is a permanent refusal — wrong domain, unverified email. The UI must not invite a retry
    // that cannot succeed.
    throw new SignInError(message, response.status === 403);
  }

  const body = (await response.json()) as SessionResponse;
  return { email: body.email, displayName: body.displayName, roles: body.roles ?? [] };
}

export async function signOut(): Promise<void> {
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
}
