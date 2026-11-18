import { useEffect, useMemo, useState } from "react";
import { AdminApi } from "./api";
import { AuthError, Session, completeLogin, loginUrl } from "./auth";
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
  return (
    <>
      <header className="bar">
        <h1>YouScout Admin</h1>
        <span className="muted">{session.displayName}</span>
        <button className="secondary" onClick={() => logout(session)}>Se déconnecter</button>
      </header>
      {session.isAdmin && api ? <SkillsPage api={api} /> : (
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
