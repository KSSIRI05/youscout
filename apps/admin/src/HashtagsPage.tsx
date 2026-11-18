import { FormEvent, useCallback, useEffect, useState } from "react";
import { AdminApi, ApiError, Hashtag, PAGE_SIZE } from "./api";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/** A04 : désactiver un hashtag le retire des recherches, des abonnements et des nouvelles vidéos ; il reste réactivable. */
export function HashtagsPage({ api }: { api: AdminApi }) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [hashtags, setHashtags] = useState<Hashtag[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await api.listHashtags(query, offset);
      setHashtags(page.items);
      setHasMore(page.hasMore);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setLoading(false);
    }
  }, [api, query, offset]);

  useEffect(() => { void load(); }, [load]);

  async function toggle(hashtag: Hashtag) {
    setBusy(current => new Set(current).add(hashtag.id));
    setError(null);
    setNotice(null);
    try {
      const saved = await api.setHashtagActive(hashtag.id, !hashtag.active);
      setHashtags(current => current.map(item => (item.id === saved.id ? saved : item)));
      setNotice(saved.active ? `#${saved.name} réactivé.` : `#${saved.name} désactivé : il n'est plus proposé aux joueurs.`);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(current => { const next = new Set(current); next.delete(hashtag.id); return next; });
    }
  }

  function applySearch(event: FormEvent) {
    event.preventDefault();
    setOffset(0);
    setQuery(search.trim());
  }

  return (
    <main className="page">
      <section className="panel">
        <div className="toolbar">
          <h2>Hashtags</h2>
          <form role="search" onSubmit={applySearch}>
            <input aria-label="Rechercher un hashtag" placeholder="Rechercher…" value={search} maxLength={80}
                   onChange={event => setSearch(event.target.value)} />
            <button type="submit">Rechercher</button>
          </form>
        </div>
        {notice && <p className="notice" role="status">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {loading ? <p className="muted">Chargement…</p> : hashtags.length === 0 ? (
          <p className="muted">{query ? `Aucun hashtag ne correspond à « ${query} ».` : "Aucun hashtag."}</p>
        ) : (
          <table>
            <thead><tr><th>Hashtag</th><th>État</th><th>Modifié</th><th aria-label="Actions" /></tr></thead>
            <tbody>
              {hashtags.map(hashtag => (
                <tr key={hashtag.id} className={hashtag.active ? undefined : "inactive"}>
                  <td>#{hashtag.name}</td>
                  <td><span className={`badge ${hashtag.active ? "on" : "off"}`}>{hashtag.active ? "Actif" : "Désactivé"}</span></td>
                  <td>{new Date(hashtag.updatedAt).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })}</td>
                  <td className="actions">
                    <button className={hashtag.active ? "danger" : undefined} disabled={busy.has(hashtag.id)}
                            aria-label={`${hashtag.active ? "Désactiver" : "Réactiver"} #${hashtag.name}`} onClick={() => toggle(hashtag)}>
                      {hashtag.active ? "Désactiver" : "Réactiver"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <button className="secondary" disabled={loading || offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Précédents</button>
          <span className="muted">Page {offset / PAGE_SIZE + 1}</span>
          <button className="secondary" disabled={loading || !hasMore} onClick={() => setOffset(offset + PAGE_SIZE)}>Suivants</button>
        </div>
      </section>
    </main>
  );
}
