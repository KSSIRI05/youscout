import { useEffect, useState } from "react";
import { Activity, ActivityDay, AdminApi, ApiError, Totals } from "./api";

type Loaded<T> = { value?: T; error?: string };
const failure = (error: unknown) => (error instanceof ApiError ? error.message : "Indisponible.");
const format = (n: number) => n.toLocaleString("fr-FR");

const SERIES: { key: keyof Omit<ActivityDay, "day">; label: string }[] = [
  { key: "newAccounts", label: "Nouveaux comptes" },
  { key: "videosPublished", label: "Vidéos publiées" },
  { key: "likes", label: "Likes" },
  { key: "comments", label: "Commentaires" },
  { key: "messages", label: "Messages" },
];

/**
 * A06 : totaux exacts lus auprès de chaque service propriétaire, et activité quotidienne comptée par Analytics.
 * Une source indisponible n'empêche pas d'afficher les autres.
 */
export function DashboardPage({ api }: { api: AdminApi }) {
  const [accounts, setAccounts] = useState<Loaded<Totals["accounts"]>>({});
  const [videos, setVideos] = useState<Loaded<Totals["videos"]>>({});
  const [interactions, setInteractions] = useState<Loaded<Totals["interactions"]>>({});
  const [ratings, setRatings] = useState<Loaded<Totals["ratings"]>>({});
  const [activity, setActivity] = useState<Loaded<Activity>>({});

  useEffect(() => {
    const load = <T,>(request: Promise<T>, set: (value: Loaded<T>) => void) =>
      request.then(value => set({ value }), error => set({ error: failure(error) }));
    load(api.accountTotals().then(t => t.accounts), setAccounts);
    load(api.videoTotals(), setVideos);
    load(api.interactionTotals(), setInteractions);
    load(api.ratingTotals(), setRatings);
    load(api.activity(30), setActivity);
  }, [api]);

  return (
    <main className="page">
      <section className="panel" aria-label="Totaux">
        <h2>Vue d'ensemble</h2>
        <div className="tiles">
          <Tile title="Comptes" source={accounts} main={a => a.ACTIVE + a.DEACTIVATED + a.CLOSING}
                detail={a => `${format(a.ACTIVE)} actifs · ${format(a.DEACTIVATED)} désactivés · ${format(a.CLOSING + a.CLOSED)} fermés ou en fermeture`} />
          <Tile title="Vidéos publiées" source={videos} main={v => v.published}
                detail={v => `${format(v.videos)} au total · ${format(v.removed)} retirées par la modération`} />
          <Tile title="Commentaires" source={interactions} main={i => i.comments} detail={i => `${format(i.likes)} likes`} />
          <Tile title="Notes" source={ratings} main={r => r.ratings}
                detail={r => `${format(r.raters)} évaluateurs · ${format(r.ratedVideos)} vidéos notées`} />
          <Tile title="Signalements ouverts" source={interactions} main={i => i.openReports} detail={() => "vidéos et commentaires"} />
        </div>
      </section>
      <section className="panel" aria-label="Activité">
        <h2>Activité des 30 derniers jours</h2>
        {activity.error && <p className="error" role="alert">Activité indisponible : {activity.error}</p>}
        {!activity.value && !activity.error && <p className="muted">Chargement…</p>}
        {activity.value && <ActivityView activity={activity.value} />}
      </section>
    </main>
  );
}

function Tile<T>({ title, source, main, detail }: { title: string; source: Loaded<T>; main: (v: T) => number; detail: (v: T) => string }) {
  return (
    <article className="tile">
      <h3>{title}</h3>
      {source.value !== undefined ? (
        <>
          <p className="figure">{format(main(source.value))}</p>
          <p className="muted">{detail(source.value)}</p>
        </>
      ) : <p className="muted">{source.error ? `Indisponible : ${source.error}` : "Chargement…"}</p>}
    </article>
  );
}

function ActivityView({ activity }: { activity: Activity }) {
  const day = (iso: string) => new Date(iso + "T00:00:00Z").toLocaleDateString("fr-FR", { day: "numeric", month: "short", timeZone: "UTC" });
  const longDay = (iso: string) => new Date(iso + "T00:00:00Z").toLocaleDateString("fr-FR", { day: "numeric", month: "long", timeZone: "UTC" });
  return (
    <>
      <p className="muted">
        Jours UTC du {longDay(activity.from)} au {longDay(activity.to)}.{" "}
        {activity.coverageStart
          ? `Comptage depuis le ${longDay(activity.coverageStart)} : les jours précédents ne sont pas reconstruits.`
          : "Aucune activité comptée pour l'instant."}
      </p>
      <div className="sparks">
        {SERIES.map(series => {
          const values = activity.days.map(d => d[series.key]);
          const total = values.reduce((a, b) => a + b, 0), max = Math.max(1, ...values);
          return (
            <figure key={series.key} className="spark">
              <figcaption><strong>{format(total)}</strong> {series.label.toLowerCase()}</figcaption>
              <svg viewBox={`0 0 ${values.length * 4} 24`} preserveAspectRatio="none" role="img"
                   aria-label={`${series.label} par jour, maximum ${format(Math.max(...values))}`}>
                {values.map((v, i) => <rect key={i} x={i * 4 + 0.5} y={24 - (v / max) * 24} width={3} height={(v / max) * 24}><title>{`${day(activity.days[i].day)} : ${v}`}</title></rect>)}
              </svg>
            </figure>
          );
        })}
      </div>
      <details>
        <summary>Tableau par jour</summary>
        <table>
          <thead><tr><th>Jour</th>{SERIES.map(s => <th key={s.key}>{s.label}</th>)}</tr></thead>
          <tbody>
            {[...activity.days].reverse().map(d => (
              <tr key={d.day}><td>{day(d.day)}</td>{SERIES.map(s => <td key={s.key}>{format(d[s.key])}</td>)}</tr>
            ))}
          </tbody>
        </table>
      </details>
    </>
  );
}
