import { ReactElement, useCallback, useEffect, useState } from "react";
import { AdminApi, AdminVideo, ApiError, ReportStatus, ReportTarget, ReportType } from "./api";

const STATUSES: { value: ReportStatus; label: string }[] = [
  { value: "OPEN", label: "À traiter" },
  { value: "REVIEWED", label: "Action prise" },
  { value: "DISMISSED", label: "Classés sans suite" },
];
const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/** A03/A04 : signalements de vidéos et de commentaires, et décisions de modération. */
export function ModerationPage({ api, open = (url: string) => window.open(url, "_blank", "noopener,noreferrer") }:
  { api: AdminApi; open?: (url: string) => void }) {
  const [type, setType] = useState<ReportType>("VIDEO");
  const [status, setStatus] = useState<ReportStatus>("OPEN");
  const [offset, setOffset] = useState(0);
  const [targets, setTargets] = useState<ReportTarget[]>([]);
  const [videos, setVideos] = useState<Record<string, AdminVideo | null>>({});
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await api.listReports(type, status, offset);
      setTargets(page.items);
      setHasMore(page.hasMore);
      // Titre, propriétaire et état de modération viennent de Video ; une vidéo supprimée depuis reste affichée sans détails.
      const ids = [...new Set(page.items.map(item => item.videoId))];
      const details = await Promise.all(ids.map(id => api.adminVideo(id).catch(() => null)));
      setVideos(Object.fromEntries(ids.map((id, index) => [id, details[index]])));
    } catch (failure) {
      setError(message(failure));
    } finally {
      setLoading(false);
    }
  }, [api, type, status, offset]);

  useEffect(() => { void load(); }, [load]);

  async function act(target: ReportTarget, action: () => Promise<unknown>, done: string) {
    setBusy(current => new Set(current).add(target.targetId));
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
      await load();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(current => { const next = new Set(current); next.delete(target.targetId); return next; });
    }
  }

  async function watch(videoId: string) {
    setError(null);
    try { open((await api.adminPlayback(videoId)).videoUrl); }
    catch (failure) { setError(message(failure)); }
  }

  const note = (target: ReportTarget) => notes[target.targetId] ?? "";

  function actions(target: ReportTarget) {
    const video = videos[target.videoId];
    const disabled = busy.has(target.targetId);
    const list: ReactElement[] = [];
    if (target.type === "VIDEO" && video) {
      list.push(<button key="watch" className="secondary" onClick={() => watch(target.videoId)}>Regarder</button>);
      if (status === "OPEN" && video.moderationStatus === "ACTIVE")
        list.push(<button key="takedown" className="danger" disabled={disabled}
          onClick={() => act(target, async () => {
            await api.takeDown(target.videoId, note(target));
            await api.decide("VIDEO", target.targetId, "ACTION_TAKEN", note(target));
          }, `Vidéo « ${video.title} » retirée ; les signalements sont fermés.`)}>Retirer la vidéo</button>);
      if (video.moderationStatus === "REMOVED")
        list.push(<button key="restore" disabled={disabled}
          onClick={() => act(target, () => api.restore(target.videoId, note(target)), `Vidéo « ${video.title} » rétablie.`)}>Rétablir</button>);
    }
    if (target.type === "COMMENT" && status === "OPEN" && !target.commentRemoved)
      list.push(<button key="remove" className="danger" disabled={disabled}
        onClick={() => act(target, () => api.removeComment(target.targetId, note(target)), "Commentaire retiré ; les signalements sont fermés.")}>
        Retirer le commentaire</button>);
    if (status === "OPEN")
      list.push(<button key="dismiss" className="secondary" disabled={disabled}
        onClick={() => act(target, () => api.decide(target.type, target.targetId, "DISMISSED", note(target)), "Signalements classés sans suite.")}>
        Classer sans suite</button>);
    return list;
  }

  return (
    <main className="page">
      <section className="panel">
        <div className="toolbar">
          <h2>Signalements</h2>
          <div className="tabs" role="tablist" aria-label="Type de contenu">
            {(["VIDEO", "COMMENT"] as ReportType[]).map(value => (
              <button key={value} role="tab" aria-selected={type === value} className={type === value ? undefined : "secondary"}
                      onClick={() => { setType(value); setOffset(0); }}>{value === "VIDEO" ? "Vidéos" : "Commentaires"}</button>
            ))}
          </div>
          <label className="inline">
            État
            <select value={status} onChange={event => { setStatus(event.target.value as ReportStatus); setOffset(0); }}>
              {STATUSES.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
        </div>
        {notice && <p className="notice" role="status">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {loading ? <p className="muted">Chargement…</p> : targets.length === 0 ? (
          <p className="muted">Aucun signalement dans cette catégorie.</p>
        ) : targets.map(target => {
          const video = videos[target.videoId];
          return (
            <article key={target.targetId} className="report">
              <header>
                <strong>
                  {target.type === "VIDEO" ? (video ? video.title : "Vidéo supprimée ou introuvable") : `« ${target.commentBody} »`}
                </strong>
                <span className="badge">{target.reportCount} signalement{target.reportCount > 1 ? "s" : ""}</span>
                {video?.moderationStatus === "REMOVED" && <span className="badge off">Vidéo retirée</span>}
                {target.commentRemoved && <span className="badge off">Commentaire retiré</span>}
              </header>
              <p className="muted">
                {target.type === "VIDEO"
                  ? video && `Propriétaire : ${video.ownerSubject}`
                  : `Auteur : ${target.commentAuthor} · sur ${video ? `« ${video.title} »` : "une vidéo supprimée"}`}
              </p>
              <ul>
                {target.reports.map(report => (
                  <li key={report.reporterSubject + report.createdAt}>
                    {report.reason} <span className="muted">— {report.reporterSubject}, {new Date(report.createdAt).toLocaleString("fr-FR")}</span>
                  </li>
                ))}
              </ul>
              {actions(target).length > 0 && (
                <div className="actions">
                  <input aria-label={`Note pour ${target.targetId}`} placeholder="Note (facultative)" maxLength={500}
                         value={note(target)} onChange={event => setNotes({ ...notes, [target.targetId]: event.target.value })} />
                  {actions(target)}
                </div>
              )}
            </article>
          );
        })}
        <div className="pager">
          <button className="secondary" disabled={loading || offset === 0} onClick={() => setOffset(Math.max(0, offset - 20))}>Précédents</button>
          <button className="secondary" disabled={loading || !hasMore} onClick={() => setOffset(offset + 20)}>Suivants</button>
        </div>
      </section>
    </main>
  );
}
