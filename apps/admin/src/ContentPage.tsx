import { FormEvent, useCallback, useEffect, useState } from "react";
import { AdminApi, AdminComment, AdminVideoRow, ApiError, LIST_SIZE } from "./api";

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");
const date = (iso: string | null) => (iso ? new Date(iso).toLocaleString("fr-FR") : "—");

/** A02 : toutes les vidéos et tous les commentaires avec leurs auteurs ; les noms viennent de User. */
export function ContentPage({ api, onOpenAccount }: { api: AdminApi; onOpenAccount: (subject: string) => void }) {
  const [tab, setTab] = useState<"videos" | "comments">("videos");
  return (
    <main className="page">
      <section className="panel">
        <div className="toolbar">
          <h2>Contenus</h2>
          <div className="tabs" role="tablist">
            <button role="tab" aria-selected={tab === "videos"} className={tab === "videos" ? undefined : "secondary"} onClick={() => setTab("videos")}>Vidéos</button>
            <button role="tab" aria-selected={tab === "comments"} className={tab === "comments" ? undefined : "secondary"} onClick={() => setTab("comments")}>Commentaires</button>
          </div>
        </div>
        {tab === "videos" ? <Videos api={api} onOpenAccount={onOpenAccount} /> : <Comments api={api} onOpenAccount={onOpenAccount} />}
      </section>
    </main>
  );
}

/** Liste paginée par curseur, avec les noms d'auteur chargés pour chaque page. */
function usePaged<T extends { id: string }>(fetch: (before?: string) => Promise<T[]>, authors: (item: T) => string, api: AdminApi) {
  const [items, setItems] = useState<T[]>([]);
  const [names, setNames] = useState<Record<string, string>>({});
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async (before?: string) => {
    setBusy(true);
    setError(null);
    try {
      const page = await fetch(before);
      setItems(current => (before ? [...current, ...page] : page));
      setMore(page.length === LIST_SIZE);
      try {
        const found = await api.accountNames(page.map(authors));
        setNames(current => ({ ...current, ...found }));
      } catch { /* Les identifiants restent affichés sans nom. */ }
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }, [fetch, authors, api]);
  useEffect(() => { void load(); }, [load]);
  return { items, names, more, busy, error, next: () => load(items[items.length - 1]?.id) };
}

function Author({ subject, names, onOpenAccount }: { subject: string; names: Record<string, string>; onOpenAccount: (s: string) => void }) {
  return <button className="link secondary" onClick={() => onOpenAccount(subject)} title={subject}>{names[subject] ?? "Compte supprimé"}</button>;
}

function Videos({ api, onOpenAccount }: { api: AdminApi; onOpenAccount: (s: string) => void }) {
  const [owner, setOwner] = useState("");
  const [publication, setPublication] = useState<"" | "PUBLISHED" | "UNPUBLISHED">("");
  const [removed, setRemoved] = useState(false);
  const [filter, setFilter] = useState({ owner: "", publication: "" as "" | "PUBLISHED" | "UNPUBLISHED", removed: false });
  const fetch = useCallback((before?: string) => api.listVideos({ ...filter, before }), [api, filter]);
  const authors = useCallback((v: AdminVideoRow) => v.ownerSubject, []);
  const list = usePaged(fetch, authors, api);
  const submit = (event: FormEvent) => { event.preventDefault(); setFilter({ owner, publication, removed }); };
  return (
    <>
      <form className="toolbar" onSubmit={submit} aria-label="Filtres des vidéos">
        <input aria-label="Identifiant du propriétaire" placeholder="Identifiant du propriétaire" value={owner} onChange={e => setOwner(e.target.value)} />
        <select aria-label="Publication" value={publication} onChange={e => setPublication(e.target.value as typeof publication)}>
          <option value="">Publiées ou non</option><option value="PUBLISHED">Publiées</option><option value="UNPUBLISHED">Non publiées</option>
        </select>
        <label className="inline"><input type="checkbox" checked={removed} onChange={e => setRemoved(e.target.checked)} style={{ width: "auto" }} /> Retirées seulement</label>
        <button type="submit" disabled={list.busy}>Filtrer</button>
      </form>
      {list.error && <p className="error" role="alert">{list.error}</p>}
      <table>
        <thead><tr><th>Titre</th><th>Auteur</th><th>État</th><th>Créée le</th></tr></thead>
        <tbody>
          {list.items.map(video => (
            <tr key={video.id}>
              <td>{video.title}<div className="muted">{video.id}</div></td>
              <td><Author subject={video.ownerSubject} names={list.names} onOpenAccount={onOpenAccount} /></td>
              <td>
                <span className={`badge ${video.publicationStatus === "PUBLISHED" ? "on" : "off"}`}>{video.publicationStatus === "PUBLISHED" ? "Publiée" : video.processingStatus ?? "Envoi"}</span>
                {video.moderationStatus === "REMOVED" && <span className="badge off"> Retirée</span>}
              </td>
              <td>{date(video.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!list.busy && list.items.length === 0 && !list.error && <p className="muted">Aucune vidéo ne correspond.</p>}
      {list.more && <button className="secondary" disabled={list.busy} onClick={list.next}>Vidéos suivantes</button>}
    </>
  );
}

function Comments({ api, onOpenAccount }: { api: AdminApi; onOpenAccount: (s: string) => void }) {
  const [videoId, setVideoId] = useState("");
  const [author, setAuthor] = useState("");
  const [filter, setFilter] = useState({ videoId: "", author: "" });
  const fetch = useCallback((before?: string) => api.listComments({ ...filter, before }), [api, filter]);
  const authors = useCallback((c: AdminComment) => c.authorSubject, []);
  const list = usePaged(fetch, authors, api);
  const submit = (event: FormEvent) => { event.preventDefault(); setFilter({ videoId, author }); };
  return (
    <>
      <form className="toolbar" onSubmit={submit} aria-label="Filtres des commentaires">
        <input aria-label="Identifiant de la vidéo" placeholder="Identifiant de la vidéo" value={videoId} onChange={e => setVideoId(e.target.value)} />
        <input aria-label="Identifiant de l'auteur" placeholder="Identifiant de l'auteur" value={author} onChange={e => setAuthor(e.target.value)} />
        <button type="submit" disabled={list.busy}>Filtrer</button>
      </form>
      {list.error && <p className="error" role="alert">{list.error}</p>}
      <table>
        <thead><tr><th>Commentaire</th><th>Auteur</th><th>Vidéo</th><th>Le</th></tr></thead>
        <tbody>
          {list.items.map(comment => (
            <tr key={comment.id} className={comment.deleted ? "inactive" : undefined}>
              <td>
                {comment.body || <span className="muted">(texte effacé)</span>}
                {comment.parentId && <span className="badge off"> Réponse</span>}
                {comment.removedByModeration ? <span className="badge off"> Retiré par la modération</span>
                  : comment.deleted && <span className="badge off"> Supprimé</span>}
              </td>
              <td><Author subject={comment.authorSubject} names={list.names} onOpenAccount={onOpenAccount} /></td>
              <td className="muted">{comment.videoId}</td>
              <td>{date(comment.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {!list.busy && list.items.length === 0 && !list.error && <p className="muted">Aucun commentaire ne correspond.</p>}
      {list.more && <button className="secondary" disabled={list.busy} onClick={list.next}>Commentaires suivants</button>}
    </>
  );
}
