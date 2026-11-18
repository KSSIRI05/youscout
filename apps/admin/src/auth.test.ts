import { AuthError, Session, challenge, claims, completeLogin, config, loginUrl, randomToken } from "./auth";

const token = (payload: object) => `h.${btoa(JSON.stringify(payload)).replace(/=+$/, "")}.s`;
const tokens = (access = token({ realm_access: { roles: ["user", "admin"] }, preferred_username: "coach" })) =>
  ({ access_token: access, refresh_token: "refresh-1", expires_in: 300 });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

describe("PKCE", () => {
  it("S256 matches the RFC 7636 test vector", async () => {
    expect(await challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });

  it("random tokens are URL-safe and fresh", () => {
    const a = randomToken(), b = randomToken();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(a).not.toBe(b);
  });

  it("login URL uses the admin client, S256 and the local callback", async () => {
    const url = new URL(await loginUrl("http://localhost:7358", sessionStorage));
    expect(url.origin + url.pathname).toBe(`${config.issuer}/protocol/openid-connect/auth`);
    expect(url.searchParams.get("client_id")).toBe("youscout-admin");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("redirect_uri")).toBe("http://localhost:7358/callback");
    expect(url.searchParams.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});

describe("callback", () => {
  async function start(now = 1_000_000) {
    const url = new URL(await loginUrl("http://localhost:7358", sessionStorage, now));
    return url.searchParams.get("state")!;
  }

  it("exchanges the code with the stored verifier and consumes the attempt", async () => {
    const state = await start();
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      const form = new URLSearchParams(init!.body as URLSearchParams);
      expect(form.get("grant_type")).toBe("authorization_code");
      expect(form.get("client_id")).toBe("youscout-admin");
      expect(form.get("code")).toBe("abc");
      expect(form.get("code_verifier")).toMatch(/^[A-Za-z0-9_-]{43}$/);
      return json(tokens());
    });
    const session = await completeLogin(new URL(`http://localhost:7358/callback?code=abc&state=${state}`),
      sessionStorage, fetchImpl, 1_000_500);
    expect(session.isAdmin).toBe(true);
    expect(session.displayName).toBe("coach");
    await expect(completeLogin(new URL(`http://localhost:7358/callback?code=abc&state=${state}`),
      sessionStorage, fetchImpl, 1_000_600)).rejects.toBeInstanceOf(AuthError);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("rejects a wrong state, a Keycloak error and an expired attempt without calling Keycloak", async () => {
    const fetchImpl = vi.fn();
    await start();
    await expect(completeLogin(new URL("http://localhost:7358/callback?code=abc&state=forged"), sessionStorage, fetchImpl, 1_000_100))
      .rejects.toThrow("invalide");
    const state = await start();
    await expect(completeLogin(new URL(`http://localhost:7358/callback?error=access_denied&state=${state}`), sessionStorage, fetchImpl))
      .rejects.toThrow("annulée");
    const old = await start(1_000_000);
    await expect(completeLogin(new URL(`http://localhost:7358/callback?code=abc&state=${old}`), sessionStorage, fetchImpl, 1_000_000 + 11 * 60_000))
      .rejects.toThrow("expiré");
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

describe("session", () => {
  it("reads roles for display and treats a user-only token as non-admin", () => {
    expect(new Session(tokens(token({ realm_access: { roles: ["user"] } })), vi.fn(), 0).isAdmin).toBe(false);
    expect(claims("not-a-jwt")).toEqual({});
  });

  it("refreshes once shortly before expiry and rotates the refresh token", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      const form = new URLSearchParams(init!.body as URLSearchParams);
      expect(form.get("grant_type")).toBe("refresh_token");
      return json({ access_token: "access-2", refresh_token: "refresh-2", expires_in: 300 });
    });
    const session = new Session(tokens("access-1"), fetchImpl, 0);
    expect(await session.accessToken(100_000)).toBe("access-1");
    const [a, b] = await Promise.all([session.accessToken(280_000), session.accessToken(280_000)]);
    expect([a, b]).toEqual(["access-2", "access-2"]);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("fails clearly when the refresh is refused", async () => {
    const session = new Session(tokens("access-1"), vi.fn(async () => json({ error: "invalid_grant" }, 400)), 0);
    await expect(session.accessToken(400_000)).rejects.toBeInstanceOf(AuthError);
  });
});
