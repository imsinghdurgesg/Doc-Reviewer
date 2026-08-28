import { render, screen, waitFor } from "@testing-library/react";
import { AuthGate } from "@/auth/AuthGate";

/**
 * What the user actually sees for each server configuration. Architecture §33.
 *
 * The two failures this pins were both reported from a running browser, not caught by the unit
 * tests underneath:
 *
 * - With SSO **off**, a sign-in screen appeared at all. Nothing about demo mode can be signed into,
 *   so the screen is a dead end — the application must render directly.
 * - With SSO **on** and a client id configured, the screen still said "Sign-in is not configured".
 *   That warning is for a genuinely missing client id and must not fire when one was supplied.
 */

const originalFetch = global.fetch;

/** Routes each endpoint the gate calls, so the two fetches cannot be confused for one another. */
function mockServer(config: unknown, sessionStatus: number) {
  global.fetch = jest.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/api/auth/config")) {
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(config) });
    }
    if (url.includes("/api/auth/session")) {
      return Promise.resolve({
        ok: sessionStatus === 200,
        status: sessionStatus,
        json: () =>
          Promise.resolve({ email: "a@stanford.edu", displayName: "A", roles: ["REVIEWER"] }),
      });
    }
    return Promise.reject(new Error(`unexpected fetch: ${url}`));
  }) as unknown as typeof fetch;
}

afterEach(() => {
  global.fetch = originalFetch;
});

describe("AuthGate", () => {
  it("renders the application and no sign-in screen when SSO is disabled", async () => {
    mockServer({ ssoEnabled: false, clientId: null, hostedDomain: null }, 401);

    render(
      <AuthGate>
        <div>Application</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByText("Application")).toBeInTheDocument());
    // The regression: a warning about a client id that demo mode neither has nor needs.
    expect(screen.queryByText(/Sign-in is not configured/)).not.toBeInTheDocument();
  });

  it("does not warn about a missing client id when one is configured", async () => {
    mockServer(
      { ssoEnabled: true, clientId: "test-client-id", hostedDomain: "stanford.edu" },
      401,
    );

    render(
      <AuthGate>
        <div>Application</div>
      </AuthGate>,
    );

    // The sign-in screen is correct here — nobody is signed in — but the "not configured" warning
    // is not, because a client id was supplied.
    await waitFor(() =>
      expect(screen.getByText(/Restricted to stanford.edu accounts/)).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Sign-in is not configured/)).not.toBeInTheDocument();
    expect(screen.queryByText("Application")).not.toBeInTheDocument();
  });

  it("warns only when SSO is on and no client id was supplied", async () => {
    // A genuine misconfiguration: mode says sso, but nothing to sign in with.
    mockServer({ ssoEnabled: true, clientId: null, hostedDomain: "stanford.edu" }, 401);

    render(
      <AuthGate>
        <div>Application</div>
      </AuthGate>,
    );

    await waitFor(() =>
      expect(screen.getByText(/Sign-in is not configured/)).toBeInTheDocument(),
    );
  });

  it("renders the application for a signed-in user", async () => {
    mockServer(
      { ssoEnabled: true, clientId: "test-client-id", hostedDomain: "stanford.edu" },
      200,
    );

    render(
      <AuthGate>
        <div>Application</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByText("Application")).toBeInTheDocument());
  });
});
