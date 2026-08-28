import {
  fetchAuthConfig,
  fetchSession,
  signInWithGoogle,
  SignInError,
  type AuthConfig,
} from "@/auth/session";

/**
 * Session discovery and sign-in. Architecture §33.
 *
 * The SPA must work against a server with SSO on or off, and the server declares which. The
 * regression these guard: the client used to INFER the mode from status codes, and the demo chain
 * answers 401 for a path that does not exist — so demo users were shown a sign-in screen that could
 * never succeed. Disabling SSO must produce no login screen at all.
 */

const SSO_ON: AuthConfig = {
  ssoEnabled: true,
  clientId: "test-client-id",
  hostedDomain: "stanford.edu",
};
const SSO_OFF: AuthConfig = { ssoEnabled: false, clientId: null, hostedDomain: null };

const originalFetch = global.fetch;

function mockFetch(response: Partial<Response> & { status: number; json?: () => Promise<unknown> }) {
  global.fetch = jest.fn().mockResolvedValue({
    ok: response.status >= 200 && response.status < 300,
    status: response.status,
    json: response.json ?? (() => Promise.resolve({})),
  }) as unknown as typeof fetch;
}

afterEach(() => {
  global.fetch = originalFetch;
});

describe("fetchAuthConfig", () => {
  it("reports what the server declares", async () => {
    mockFetch({ status: 200, json: () => Promise.resolve(SSO_ON) });

    await expect(fetchAuthConfig()).resolves.toEqual(SSO_ON);
  });

  it("reports SSO off when the endpoint cannot be reached", async () => {
    // Showing the application is recoverable; a sign-in screen with no client id is a dead end.
    global.fetch = jest.fn().mockRejectedValue(new Error("offline")) as unknown as typeof fetch;

    await expect(fetchAuthConfig()).resolves.toEqual(SSO_OFF);
  });
});

describe("fetchSession", () => {
  it("skips the session check entirely when SSO is disabled", async () => {
    // The operator turned login off. No request, no sign-in screen.
    const spy = jest.fn();
    global.fetch = spy as unknown as typeof fetch;

    const state = await fetchSession(SSO_OFF);

    expect(state.status).toBe("authenticated");
    expect(spy).not.toHaveBeenCalled();
  });

  it("treats 401 as not signed in when SSO is enabled", async () => {
    mockFetch({ status: 401 });

    const state = await fetchSession(SSO_ON);

    expect(state.status).toBe("anonymous");
  });

  it("returns the user on 200", async () => {
    mockFetch({
      status: 200,
      json: () =>
        Promise.resolve({
          email: "imsingh@stanford.edu",
          displayName: "I M Singh",
          roles: ["REVIEWER", "ADMIN"],
        }),
    });

    const state = await fetchSession(SSO_ON);

    expect(state).toEqual({
      status: "authenticated",
      user: {
        email: "imsingh@stanford.edu",
        displayName: "I M Singh",
        roles: ["REVIEWER", "ADMIN"],
      },
    });
  });

  it("falls back to anonymous when the network fails", async () => {
    global.fetch = jest.fn().mockRejectedValue(new Error("offline")) as unknown as typeof fetch;

    const state = await fetchSession(SSO_ON);

    expect(state.status).toBe("anonymous");
  });

  it("tolerates a response with no roles array", async () => {
    mockFetch({
      status: 200,
      json: () => Promise.resolve({ email: "a@stanford.edu", displayName: "A" }),
    });

    const state = await fetchSession(SSO_ON);

    expect(state.status).toBe("authenticated");
    if (state.status === "authenticated") {
      expect(state.user.roles).toEqual([]);
    }
  });
});

describe("signInWithGoogle", () => {
  it("returns the user on success", async () => {
    mockFetch({
      status: 200,
      json: () =>
        Promise.resolve({ email: "a@stanford.edu", displayName: "A", roles: ["REVIEWER"] }),
    });

    await expect(signInWithGoogle("token")).resolves.toEqual({
      email: "a@stanford.edu",
      displayName: "A",
      roles: ["REVIEWER"],
    });
  });

  it("marks a 403 as permanent and surfaces the server's reason", async () => {
    // Wrong domain or unverified email. Retrying can never succeed, so the UI must not invite it —
    // and the server's message is the only thing that explains why.
    mockFetch({
      status: 403,
      json: () =>
        Promise.resolve({ message: "This application is restricted to stanford.edu accounts." }),
    });

    await expect(signInWithGoogle("token")).rejects.toMatchObject({
      permanent: true,
      message: "This application is restricted to stanford.edu accounts.",
    });
  });

  it("marks a 401 as retryable", async () => {
    // An expired or malformed token. Signing in again genuinely helps.
    mockFetch({ status: 401, json: () => Promise.resolve({ message: "Could not verify." }) });

    await expect(signInWithGoogle("token")).rejects.toMatchObject({ permanent: false });
  });

  it("survives a non-JSON error body", async () => {
    // A proxy error page. A JSON parse failure here would replace the real problem with a
    // confusing one.
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: () => Promise.reject(new Error("not json")),
    }) as unknown as typeof fetch;

    await expect(signInWithGoogle("token")).rejects.toBeInstanceOf(SignInError);
  });
});
