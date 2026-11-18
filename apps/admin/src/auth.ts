// Connexion OIDC Authorization Code + PKCE (S256) auprès de Keycloak, client public youscout-admin.
// Les jetons restent en mémoire : un rechargement de page demande une nouvelle connexion.
// Seuls le verifier et le state de la tentative en cours passent par sessionStorage, le temps de la redirection.

export const config = {
  issuer: import.meta.env.VITE_OIDC_ISSUER ?? "http://localhost:8180/realms/youscout",
  clientId: "youscout-admin",
  api: import.meta.env.VITE_API_URL ?? "http://localhost:8080",
};

export class AuthError extends Error {}

type Fetch = typeof fetch;
type Attempt = { verifier: string; state: string; createdAt: number };
type TokenResponse = { access_token: string; refresh_token: string; expires_in: number };

const ATTEMPT_KEY = "youscout-admin:pkce";
const ATTEMPT_MAX_AGE_MS = 10 * 60 * 1000;
const REFRESH_MARGIN_MS = 30 * 1000;

const endpoint = (name: "auth" | "token" | "logout") => `${config.issuer}/protocol/openid-connect/${name}`;
const redirectUri = (origin: string) => `${origin}/callback`;

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function randomToken(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

export async function challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

/** Prépare une tentative et renvoie l'URL Keycloak vers laquelle rediriger le navigateur. */
export async function loginUrl(origin: string, storage: Storage = sessionStorage, now = Date.now()): Promise<string> {
  const attempt: Attempt = { verifier: randomToken(), state: randomToken(), createdAt: now };
  storage.setItem(ATTEMPT_KEY, JSON.stringify(attempt));
  const params = new URLSearchParams({
    client_id: config.clientId,
    response_type: "code",
    scope: "openid",
    redirect_uri: redirectUri(origin),
    state: attempt.state,
    code_challenge: await challenge(attempt.verifier),
    code_challenge_method: "S256",
  });
  return `${endpoint("auth")}?${params}`;
}

/** Valide le retour de Keycloak puis échange le code ; la tentative est consommée dans tous les cas. */
export async function completeLogin(callback: URL, storage: Storage = sessionStorage, fetchImpl: Fetch = fetch,
                                    now = Date.now()): Promise<Session> {
  const raw = storage.getItem(ATTEMPT_KEY);
  storage.removeItem(ATTEMPT_KEY);
  if (callback.searchParams.has("error")) throw new AuthError("Connexion annulée ou refusée par Keycloak.");
  let attempt: Attempt;
  try { attempt = JSON.parse(raw ?? ""); } catch { throw new AuthError("Aucune connexion en cours. Recommence."); }
  const code = callback.searchParams.get("code");
  if (!code || callback.searchParams.get("state") !== attempt.state)
    throw new AuthError("Réponse de connexion invalide. Recommence.");
  if (now - attempt.createdAt > ATTEMPT_MAX_AGE_MS || now < attempt.createdAt)
    throw new AuthError("La tentative de connexion a expiré. Recommence.");
  const tokens = await tokenRequest(fetchImpl, {
    grant_type: "authorization_code",
    client_id: config.clientId,
    code,
    redirect_uri: redirectUri(callback.origin),
    code_verifier: attempt.verifier,
  });
  return new Session(tokens, fetchImpl, now);
}

async function tokenRequest(fetchImpl: Fetch, form: Record<string, string>): Promise<TokenResponse> {
  let response: Response;
  try {
    response = await fetchImpl(endpoint("token"), {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form),
    });
  } catch {
    throw new AuthError("Keycloak est injoignable.");
  }
  if (!response.ok) throw new AuthError("La session n'a pas pu être ouverte ou renouvelée. Reconnecte-toi.");
  const data = (await response.json()) as Partial<TokenResponse>;
  if (typeof data.access_token !== "string" || typeof data.refresh_token !== "string" || typeof data.expires_in !== "number")
    throw new AuthError("Réponse de Keycloak invalide.");
  return data as TokenResponse;
}

/** Lecture des claims pour l'affichage uniquement : chaque API vérifie elle-même la signature et le rôle. */
export function claims(token: string): Record<string, unknown> {
  try {
    const part = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const json = new TextDecoder().decode(Uint8Array.from(atob(part.padEnd(Math.ceil(part.length / 4) * 4, "=")), c => c.charCodeAt(0)));
    return JSON.parse(json);
  } catch {
    return {};
  }
}

export class Session {
  private access: string;
  private refresh: string;
  private expiresAt: number;
  private refreshing: Promise<void> | null = null;

  constructor(tokens: TokenResponse, private readonly fetchImpl: Fetch = fetch, now = Date.now()) {
    this.access = tokens.access_token;
    this.refresh = tokens.refresh_token;
    this.expiresAt = now + tokens.expires_in * 1000;
  }

  get roles(): string[] {
    const realm = claims(this.access).realm_access as { roles?: unknown } | undefined;
    return Array.isArray(realm?.roles) ? realm.roles.filter((role): role is string => typeof role === "string") : [];
  }
  get isAdmin(): boolean { return this.roles.includes("admin"); }
  get displayName(): string {
    const data = claims(this.access);
    for (const key of ["preferred_username", "name", "sub"]) if (typeof data[key] === "string") return data[key] as string;
    return "";
  }

  /** Renvoie un jeton valide, renouvelé (avec rotation du refresh token) peu avant son expiration. */
  async accessToken(now = Date.now()): Promise<string> {
    if (now >= this.expiresAt - REFRESH_MARGIN_MS) {
      this.refreshing ??= tokenRequest(this.fetchImpl, {
        grant_type: "refresh_token", client_id: config.clientId, refresh_token: this.refresh,
      }).then(tokens => {
        this.access = tokens.access_token;
        this.refresh = tokens.refresh_token;
        this.expiresAt = Date.now() + tokens.expires_in * 1000;
      }).finally(() => { this.refreshing = null; });
      await this.refreshing;
    }
    return this.access;
  }

  /** Ferme la session Keycloak ; une erreur réseau n'empêche pas l'oubli local des jetons. */
  async logout(): Promise<void> {
    try {
      await this.fetchImpl(endpoint("logout"), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: config.clientId, refresh_token: this.refresh }),
      });
    } catch { /* Les jetons en mémoire sont abandonnés de toute façon. */ }
  }
}
