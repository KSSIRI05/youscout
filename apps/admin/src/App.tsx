import { useEffect, useMemo, useState } from "react";
import { AdminApi } from "./api";
import { AuthError, Session, completeLogin, loginUrl } from "./auth";
import { AccountsPage } from "./AccountsPage";
import { ContentPage } from "./ContentPage";
import { DashboardPage } from "./DashboardPage";
import { UsersPage } from "./UsersPage";
import { HashtagsPage } from "./HashtagsPage";
import { ModerationPage } from "./ModerationPage";
import { SkillsPage } from "./SkillsPage";

type State =
  | { kind: "loading" }
  | { kind: "signedOut"; error?: string }
  | { kind: "signedIn"; session: Session };

export type AppDeps = {
  location: Pick<Location, "href" | "origin" | "pathname" | "assign">;
  replaceUrl: (path: string) => void;
  completeLogin: (callback: URL) => Promise<Session>;
  loginUrl: (origin: string) => Promise<string>;
};

const browser: AppDeps = {
  location: window.location,
  replaceUrl: path => window.history.replaceState(null, "", path),
  completeLogin: callback => completeLogin(callback),
  loginUrl: origin => loginUrl(origin),
};

export function App({ deps = browser }: { deps?: AppDeps }) {
  const [state, setState] = useState<State>({ kind: "loading" });
  const [page, setPage] = useState<"dashboard" | "users" | "content" | "skills" | "hashtags" | "moderation" | "accounts">("skills");
  const [accountSubject, setAccountSubject] = useState("");

  useEffect(() => {
    if (deps.location.pathname !== "/callback") { setState({ kind: "signedOut" }); return; }
    const callback = new URL(deps.location.href);
    // Retire code et state de la barre d'adresse et de l'historique, quel que soit le résultat.
    deps.replaceUrl("/");
    deps.completeLogin(callback)
      .then(session => setState({ kind: "signedIn", session }))
      .catch(error => setState({
        kind: "signedOut",
        error: error instanceof AuthError ? error.message : "La connexion a échoué. Recommence.",
      }));
  }, [deps]);

  const api = useMemo(() => state.kind === "signedIn"
    ? new AdminApi(async () => {
        try { return await state.session.accessToken(); }
        catch (error) {
          setState({ kind: "signedOut", error: "Ta session a expiré. Reconnecte-toi." });
          throw error;
        }
      })
    : null, [state]);

  async function login() {
    try { deps.location.assign(await deps.loginUrl(deps.location.origin)); }
    catch { setState({ kind: "signedOut", error: "Impossible de préparer la connexion." }); }
  }

  async function logout(session: Session) {
    await session.logout();
    setState({ kind: "signedOut" });
  }

  if (state.kind === "loading") return <p className="center muted">Connexion…</p>;

  if (state.kind === "signedOut") {
    return (
      <main className="center">
        <div className="panel login">
          <h1>YouScout Admin</h1>
          <p className="muted">Gestion du catalogue des compétences. Réservé aux comptes ayant le rôle admin.</p>
          {state.error && <p className="error" role="alert">{state.error}</p>}
          <button onClick={login}>Se connecter avec Keycloak</button>
        </div>
      </main>
    );
  }

  const { session } = state;
  const openAccount = (subject: string) => { setAccountSubject(subject); setPage("accounts"); };
  return (
    <>
      <header className="bar">
        <h1>YouScout Admin</h1>
        {session.isAdmin && (
          <nav aria-label="Sections">
            <button className={page === "dashboard" ? undefined : "secondary"} aria-current={page === "dashboard" ? "page" : undefined}
                    onClick={() => setPage("dashboard")}>Tableau de bord</button>
            <button className={page === "users" ? undefined : "secondary"} aria-current={page === "users" ? "page" : undefined}
                    onClick={() => setPage("users")}>Utilisateurs</button>
            <button className={page === "content" ? undefined : "secondary"} aria-current={page === "content" ? "page" : undefined}
                    onClick={() => setPage("content")}>Contenus</button>
            <button className={page === "skills" ? undefined : "secondary"} aria-current={page === "skills" ? "page" : undefined}
                    onClick={() => setPage("skills")}>Compétences</button>
            <button className={page === "hashtags" ? undefined : "secondary"} aria-current={page === "hashtags" ? "page" : undefined}
                    onClick={() => setPage("hashtags")}>Hashtags</button>
            <button className={page === "moderation" ? undefined : "secondary"} aria-current={page === "moderation" ? "page" : undefined}
                    onClick={() => setPage("moderation")}>Signalements</button>
            <button className={page === "accounts" ? undefined : "secondary"} aria-current={page === "accounts" ? "page" : undefined}
                    onClick={() => { setAccountSubject(""); setPage("accounts"); }}>Comptes</button>
          </nav>
        )}
        <span className="muted">{session.displayName}</span>
        <button className="secondary" onClick={() => logout(session)}>Se déconnecter</button>
      </header>
      {session.isAdmin && api ? (page === "dashboard" ? <DashboardPage api={api} />
        : page === "users" ? <UsersPage api={api} onOpenAccount={openAccount} />
        : page === "content" ? <ContentPage api={api} onOpenAccount={openAccount} />
        : page === "skills" ? <SkillsPage api={api} /> : page === "hashtags" ? <HashtagsPage api={api} />
        : page === "accounts" ? <AccountsPage key={accountSubject} api={api} initialSubject={accountSubject} />
        : <ModerationPage api={api} onOpenAccount={openAccount} />) : (
        <main className="center">
          <div className="panel login">
            <h2>Accès refusé</h2>
            <p>Ce compte n'a pas le rôle admin. Demande à un administrateur de te l'attribuer dans Keycloak, puis reconnecte-toi.</p>
          </div>
        </main>
      )}
    </>
  );
}
