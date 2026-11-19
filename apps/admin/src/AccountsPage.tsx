import { FormEvent, useCallback, useEffect, useState } from "react";
import { Account, AccountClosure, AdminApi, ApiError } from "./api";

const STEP_LABELS: Record<string, string> = {
  SESSIONS: "Connexion bloquée et sessions fermées",
  VIDEO: "Vidéos supprimées",
  SOCIAL: "Likes et commentaires effacés",
  RATING: "Notes effacées",
  CHAT: "Texte des messages effacé",
  SKILL: "Abonnements aux hashtags retirés",
  NOTIFICATION: "Notifications et appareils effacés",
  IDENTITY: "Compte Keycloak supprimé",
};
const STATUS_LABELS: Record<Account["status"], string> = {
  ACTIVE: "Actif", DEACTIVATED: "Désactivé", CLOSING: "Fermeture en cours", CLOSED: "Fermé",
};

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/**
 * A04 : désactiver un compte bloque sa connexion dans Keycloak, ferme ses sessions, masque son profil et ses vidéos
 * dans le fil. La réactivation rétablit le tout. Un compte fermé par son titulaire (U22) affiche son journal de fermeture.
 */
export function AccountsPage({ api, initialSubject = "" }: { api: AdminApi; initialSubject?: string }) {
  const [subject, setSubject] = useState(initialSubject);
  const [account, setAccount] = useState<Account | null>(null);
  const [closure, setClosure] = useState<AccountClosure | null>(null);
  const [note, setNote] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const lookup = useCallback(async (value: string) => {
    if (!value.trim()) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    setConfirming(false);
    setClosure(null);
    let found: Account;
    try { found = await api.getAccount(value.trim()); setAccount(found); }
    catch (failure) { setAccount(null); setError(message(failure)); setBusy(false); return; }
    // U22 : un compte fermé par son titulaire montre son journal ; il ne se désactive ni ne se réactive plus.
    // Un journal illisible n'efface pas le compte affiché : seule l'erreur s'ajoute.
    if (found.status === "CLOSING" || found.status === "CLOSED") {
      try { setClosure(await api.getAccountClosure(found.subject)); }
      catch (failure) { setError(`Journal de fermeture indisponible : ${message(failure)}`); }
    }
    setBusy(false);
  }, [api]);

  useEffect(() => { if (initialSubject) void lookup(initialSubject); }, [initialSubject, lookup]);

  function submit(event: FormEvent) {
    event.preventDefault();
    void lookup(subject);
  }

  async function change(deactivate: boolean) {
    if (!account) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = deactivate ? await api.deactivateAccount(account.subject, note) : await api.reactivateAccount(account.subject, note);
      setAccount(saved);
      setNote("");
      setConfirming(false);
      setNotice(deactivate ? `Compte de ${saved.displayName} désactivé : connexion bloquée, profil et vidéos masqués.`
                           : `Compte de ${saved.displayName} réactivé.`);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }

  const active = account?.status === "ACTIVE";
  const closing = account?.status === "CLOSING" || account?.status === "CLOSED";
  return (
    <main className="page">
      <section className="panel">
        <div className="toolbar">
          <h2>Comptes</h2>
          <form role="search" onSubmit={submit}>
            <input aria-label="Identifiant du compte" placeholder="Identifiant (sub Keycloak)" value={subject} maxLength={255}
                   onChange={event => setSubject(event.target.value)} />
            <button type="submit" disabled={busy}>Afficher</button>
          </form>
        </div>
        <p className="muted">L'identifiant figure dans la file des signalements (propriétaire, auteur ou signaleur).</p>
        {notice && <p className="notice" role="status">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {account && (
          <article className="report">
            <header>
              <strong>{account.displayName}</strong>
              <span className={`badge ${active ? "on" : "off"}`}>{STATUS_LABELS[account.status]}</span>
            </header>
            <p className="muted">{account.subject} · inscrit le {new Date(account.createdAt).toLocaleDateString("fr-FR")}</p>
            {account.statusChangedAt && (
              <p className="muted">
                Dernier changement le {new Date(account.statusChangedAt).toLocaleString("fr-FR")} par {account.statusChangedBy}
                {account.statusNote ? ` — « ${account.statusNote} »` : ""}
              </p>
            )}
            {closure && (
              <section aria-label="Fermeture du compte">
                <p className="muted">
                  Fermeture demandée par le joueur le {new Date(closure.requestedAt).toLocaleString("fr-FR")}
                  {closure.completedAt ? `, terminée le ${new Date(closure.completedAt).toLocaleString("fr-FR")}` : ""}
                </p>
                <ul>
                  {closure.steps.map(step => (
                    <li key={step.name}>
                      {STEP_LABELS[step.name] ?? step.name} : {step.status === "DONE" ? "fait"
                        : `en attente${step.attempts ? ` (${step.attempts} essai${step.attempts > 1 ? "s" : ""}${step.lastError ? `, ${step.lastError}` : ""})` : ""}`}
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {!closing && <div className="actions">
              <input aria-label="Note" placeholder="Note (facultative)" maxLength={500} value={note} onChange={event => setNote(event.target.value)} />
              {active && !confirming && <button className="danger" disabled={busy} onClick={() => setConfirming(true)}>Désactiver le compte</button>}
              {active && confirming && (
                <>
                  <button className="danger" disabled={busy} onClick={() => change(true)}>Confirmer la désactivation</button>
                  <button className="secondary" disabled={busy} onClick={() => setConfirming(false)}>Annuler</button>
                </>
              )}
              {!active && <button disabled={busy} onClick={() => change(false)}>Réactiver le compte</button>}
            </div>}
          </article>
        )}
      </section>
    </main>
  );
}
