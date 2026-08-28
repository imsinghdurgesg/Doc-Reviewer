import { canAdminister } from "@/auth/session";

/**
 * Who may change administered settings. Architecture §33.
 *
 * <p>This mirrors the backend's {@code hasAnyRole('STANDARDS_ADMIN','ADMIN')} for one purpose only:
 * deciding whether to DISABLE a control. Before it existed, the Admin screen rendered fully for every
 * reviewer — they could fill in a provider key and only discover on Save that the backend rejected
 * them, which reads as a broken application rather than a permission boundary.
 *
 * <p>It is presentation, not enforcement: re-enabling a field in devtools still hits a backend that
 * refuses. That is precisely what makes it safe to render the form at all rather than hide it.
 */

function user(roles: string[]) {
  return { email: "someone@stanford.edu", displayName: "Someone", roles };
}

describe("canAdminister", () => {
  it("permits ADMIN", () => {
    expect(canAdminister(user(["REVIEWER", "ADMIN"]))).toBe(true);
  });

  it("permits STANDARDS_ADMIN", () => {
    // Matches the backend, where the standards and AI-config controllers accept either role.
    expect(canAdminister(user(["REVIEWER", "STANDARDS_ADMIN"]))).toBe(true);
  });

  it("refuses a plain reviewer", () => {
    expect(canAdminister(user(["REVIEWER"]))).toBe(false);
  });

  it("fails closed when there is no user", () => {
    // A session that has not loaded, or failed to. Defaulting to permitted would flash an editable
    // form before the roles arrive, and a click in that window would produce an unexplained 403.
    expect(canAdminister(null)).toBe(false);
  });

  it("fails closed on an empty role list", () => {
    expect(canAdminister(user([]))).toBe(false);
  });

  it("does not accept a lookalike role name", () => {
    // Exact membership, not a substring match: "ADMINISTRATOR" and "NOT_ADMIN" are not ADMIN, and a
    // .includes() over the joined string would wrongly admit both.
    expect(canAdminister(user(["ADMINISTRATOR"]))).toBe(false);
    expect(canAdminister(user(["NOT_ADMIN"]))).toBe(false);
    expect(canAdminister(user(["admin"]))).toBe(false);
  });
});
