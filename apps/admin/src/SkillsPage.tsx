import { FormEvent, useCallback, useEffect, useState } from "react";
import { AdminApi, ApiError, DESCRIPTION_MAX, NAME_MAX, PAGE_SIZE, Skill, SkillPatch, codePoints } from "./api";

type Draft = { name: string; description: string };

function validate(draft: Draft): string | null {
  const name = draft.name.trim();
  if (!name) return "Le nom est obligatoire.";
  if (codePoints(name) > NAME_MAX) return `Le nom dépasse ${NAME_MAX} caractères.`;
  if (codePoints(draft.description.trim()) > DESCRIPTION_MAX) return `La description dépasse ${DESCRIPTION_MAX} caractères.`;
  return null;
}

const message = (error: unknown) => (error instanceof ApiError ? error.message : "Une erreur inattendue est survenue.");

/** A05 : ajouter, modifier, désactiver et réactiver les compétences du catalogue. */
export function SkillsPage({ api }: { api: AdminApi }) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>({ name: "", description: "" });
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<{ id: string; draft: Draft } | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await api.listSkills(query, offset);
      setSkills(page.items);
      setHasMore(page.hasMore);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setLoading(false);
    }
  }, [api, query, offset]);

  useEffect(() => { void load(); }, [load]);

  const mark = (id: string, on: boolean) =>
    setBusy(current => { const next = new Set(current); if (on) next.add(id); else next.delete(id); return next; });

  async function create(event: FormEvent) {
    event.preventDefault();
    const invalid = validate(draft);
    if (invalid) { setError(invalid); return; }
    setCreating(true);
    setError(null);
    setNotice(null);
    try {
      const skill = await api.createSkill(draft.name.trim(), draft.description.trim());
      setDraft({ name: "", description: "" });
      setNotice(`Compétence « ${skill.name} » ajoutée.`);
      if (offset === 0) await load(); else setOffset(0);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setCreating(false);
    }
  }

  async function update(skill: Skill, patch: SkillPatch, done: string) {
    mark(skill.id, true);
    setError(null);
    setNotice(null);
    try {
      const saved = await api.updateSkill(skill.id, patch);
      setSkills(current => current.map(item => (item.id === saved.id ? saved : item)));
      setNotice(done.replace("{name}", saved.name));
      return true;
    } catch (failure) {
      setError(message(failure));
      return false;
    } finally {
      mark(skill.id, false);
    }
  }

  async function save(skill: Skill, edited: Draft) {
    const invalid = validate(edited);
    if (invalid) { setError(invalid); return; }
    // Seuls les champs modifiés sont envoyés.
    const patch: SkillPatch = {};
    if (edited.name.trim() !== skill.name) patch.name = edited.name.trim();
    if (edited.description.trim() !== skill.description) patch.description = edited.description.trim();
    if (Object.keys(patch).length === 0) { setEditing(null); return; }
    if (await update(skill, patch, "Compétence « {name} » modifiée.")) setEditing(null);
  }

  function applySearch(event: FormEvent) {
    event.preventDefault();
    setOffset(0);
    setQuery(search.trim());
  }

  return (
    <main className="page">
      <section className="panel">
        <h2>Ajouter une compétence</h2>
        <form className="create" onSubmit={create}>
          <label>
            Nom
            <input value={draft.name} maxLength={NAME_MAX * 2} required
                   onChange={event => setDraft({ ...draft, name: event.target.value })} />
          </label>
          <label>
            Description <span className="hint">(facultative)</span>
            <textarea value={draft.description} maxLength={DESCRIPTION_MAX * 2} rows={2}
                      onChange={event => setDraft({ ...draft, description: event.target.value })} />
          </label>
          <button type="submit" disabled={creating}>{creating ? "Ajout…" : "Ajouter"}</button>
        </form>
      </section>

      <section className="panel">
        <div className="toolbar">
          <h2>Catalogue</h2>
          <form role="search" onSubmit={applySearch}>
            <input aria-label="Rechercher une compétence" placeholder="Rechercher…" value={search} maxLength={80}
                   onChange={event => setSearch(event.target.value)} />
            <button type="submit">Rechercher</button>
          </form>
        </div>
        {notice && <p className="notice" role="status">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}
        {loading ? <p className="muted">Chargement…</p> : skills.length === 0 ? (
          <p className="muted">{query ? `Aucune compétence ne correspond à « ${query} ».` : "Le catalogue est vide."}</p>
        ) : (
          <table>
            <thead>
              <tr><th>Nom</th><th>Description</th><th>État</th><th>Modifiée</th><th aria-label="Actions" /></tr>
            </thead>
            <tbody>
              {skills.map(skill => editing?.id === skill.id ? (
                <tr key={skill.id} className="editing">
                  <td><input aria-label="Nouveau nom" value={editing.draft.name} maxLength={NAME_MAX * 2}
                             onChange={event => setEditing({ id: skill.id, draft: { ...editing.draft, name: event.target.value } })} /></td>
                  <td><textarea aria-label="Nouvelle description" rows={2} value={editing.draft.description} maxLength={DESCRIPTION_MAX * 2}
                                onChange={event => setEditing({ id: skill.id, draft: { ...editing.draft, description: event.target.value } })} /></td>
                  <td><Status active={skill.active} /></td>
                  <td>{date(skill.updatedAt)}</td>
                  <td className="actions">
                    <button disabled={busy.has(skill.id)} onClick={() => save(skill, editing.draft)}>Enregistrer</button>
                    <button className="secondary" onClick={() => setEditing(null)}>Annuler</button>
                  </td>
                </tr>
              ) : (
                <tr key={skill.id} className={skill.active ? undefined : "inactive"}>
                  <td>{skill.name}</td>
                  <td>{skill.description || <span className="muted">—</span>}</td>
                  <td><Status active={skill.active} /></td>
                  <td>{date(skill.updatedAt)}</td>
                  <td className="actions">
                    <button className="secondary" disabled={busy.has(skill.id)}
                            onClick={() => setEditing({ id: skill.id, draft: { name: skill.name, description: skill.description } })}>
                      Modifier
                    </button>
                    <button className={skill.active ? "danger" : undefined} disabled={busy.has(skill.id)}
                            aria-label={`${skill.active ? "Désactiver" : "Réactiver"} ${skill.name}`}
                            onClick={() => update(skill, { active: !skill.active },
                              skill.active ? "Compétence « {name} » désactivée : elle n'est plus proposée aux joueurs."
                                           : "Compétence « {name} » réactivée.")}>
                      {skill.active ? "Désactiver" : "Réactiver"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="pager">
          <button className="secondary" disabled={loading || offset === 0}
                  onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Précédentes</button>
          <span className="muted">Page {offset / PAGE_SIZE + 1}</span>
          <button className="secondary" disabled={loading || !hasMore || offset + PAGE_SIZE > 10000}
                  onClick={() => setOffset(offset + PAGE_SIZE)}>Suivantes</button>
        </div>
      </section>
    </main>
  );
}

function Status({ active }: { active: boolean }) {
  return <span className={`badge ${active ? "on" : "off"}`}>{active ? "Active" : "Désactivée"}</span>;
}

function date(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" });
}
