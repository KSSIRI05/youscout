import { FormEvent, useCallback, useEffect, useState } from "react";
import { Account, AccountStatus, AdminApi, ApiError, LIST_SIZE } from "./api";

const LABELS: Record<AccountStatus, string> = { ACTIVE: "Actif", DEACTIVATED: "Désactivé", CLOSING: "Fermeture en cours", CLOSED: "Fermé" };
const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/** A01 : tous les comptes, plus récents d'abord, avec recherche et filtre ; un compte s'ouvre dans la page Comptes. */
export function UsersPage({ api, onOpenAccount }: { api: AdminApi; onOpenAccount: (subject: string) => void }) {
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<AccountStatus | "">("");
  const [filter, setFilter] = useState<{ q: string; status: AccountStatus | "" }>({ q: "", status: "" });
  const [items, setItems] = useState<Account[]>([]);
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (before?: string) => {
    setBusy(true);
    setError(null);
    try {
      const page = await api.listAccounts({ ...filter, before });
      setItems(current => (before ? [...current, ...page] : page));
      setMore(page.length === LIST_SIZE);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }, [api, filter]);

  useEffect(() => { void load(); }, [load]);

  function submit(event: FormEvent) {
    event.preventDefault();
    setFilter({ q, status });
  }

  return (
    <main className="page">
      <section className="panel">
        <div className="toolbar">
          <h2>Utilisateurs</h2>
          <form role="search" onSubmit={submit}>
            <input aria-label="Nom ou identifiant" placeholder="Nom ou identifiant" value={q} maxLength={80} onChange={e => setQ(e.target.value)} />
            <select aria-label="Statut" value={status} onChange={e => setStatus(e.target.value as AccountStatus | "")}>
              <option value="">Tous les statuts</option>
              {Object.entries(LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <button type="submit" disabled={busy}>Filtrer</button>
          </form>
        </div>
        {error && <p className="error" role="alert">{error}</p>}
        <table>
          <thead><tr><th>Nom</th><th>Identifiant</th><th>Statut</th><th>Inscrit le</th><th /></tr></thead>
          <tbody>
            {items.map(account => (
              <tr key={account.subject} className={account.status === "ACTIVE" ? undefined : "inactive"}>
                <td>{account.displayName}</td>
                <td className="muted">{account.subject}</td>
                <td><span className={`badge ${account.status === "ACTIVE" ? "on" : "off"}`}>{LABELS[account.status]}</span></td>
                <td>{new Date(account.createdAt).toLocaleDateString("fr-FR")}</td>
                <td className="actions"><button className="secondary" onClick={() => onOpenAccount(account.subject)}>Ouvrir</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {!busy && items.length === 0 && !error && <p className="muted">Aucun compte ne correspond.</p>}
        {more && <button className="secondary" disabled={busy} onClick={() => load(items[items.length - 1].subject)}>Comptes suivants</button>}
      </section>
    </main>
  );
}
