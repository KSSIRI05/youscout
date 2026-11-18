import { FormEvent, useCallback, useEffect, useState } from "react";
import { Account, AdminApi, ApiError } from "./api";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/**
 * A04 : désactiver un compte bloque sa connexion dans Keycloak, ferme ses sessions, masque son profil et ses vidéos
 * dans le fil. La réactivation rétablit le tout.
 */
export function AccountsPage({ api, initialSubject = "" }: { api: AdminApi; initialSubject?: string }) {
  const [subject, setSubject] = useState(initialSubject);
  const [account, setAccount] = useState<Account | null>(null);
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
    try { setAccount(await api.getAccount(value.trim())); }
    catch (failure) { setAccount(null); setError(message(failure)); }
    finally { setBusy(false); }
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
              <span className={`badge ${active ? "on" : "off"}`}>{active ? "Actif" : "Désactivé"}</span>
            </header>
            <p className="muted">{account.subject} · inscrit le {new Date(account.createdAt).toLocaleDateString("fr-FR")}</p>
            {account.statusChangedAt && (
              <p className="muted">
                Dernier changement le {new Date(account.statusChangedAt).toLocaleString("fr-FR")} par {account.statusChangedBy}
                {account.statusNote ? ` — « ${account.statusNote} »` : ""}
              </p>
            )}
            <div className="actions">
              <input aria-label="Note" placeholder="Note (facultative)" maxLength={500} value={note} onChange={event => setNote(event.target.value)} />
              {active && !confirming && <button className="danger" disabled={busy} onClick={() => setConfirming(true)}>Désactiver le compte</button>}
              {active && confirming && (
                <>
                  <button className="danger" disabled={busy} onClick={() => change(true)}>Confirmer la désactivation</button>
                  <button className="secondary" disabled={busy} onClick={() => setConfirming(false)}>Annuler</button>
                </>
              )}
              {!active && <button disabled={busy} onClick={() => change(false)}>Réactiver le compte</button>}
            </div>
          </article>
        )}
      </section>
    </main>
  );
}
