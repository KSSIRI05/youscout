import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminApi, Skill } from "./api";
import { SkillsPage } from "./SkillsPage";

const skill = (id: string, name: string, active = true): Skill => ({
  id, name, description: `${name} description`, active,
  createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:00:00Z",
});

/** Fausse Gateway : enregistre les requêtes et sert un catalogue en mémoire. */
function fakeBackend(initial: Skill[]) {
  const catalogue = [...initial];
  const calls: { method: string; url: URL; body?: unknown; auth?: string }[] = [];
  let failNext: { status: number; detail: string } | null = null;
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init?.method ?? "GET", url, body, auth: (init?.headers as Record<string, string>).Authorization });
    if (failNext) {
      const failure = failNext; failNext = null;
      return new Response(JSON.stringify({ detail: failure.detail }), { status: failure.status });
    }
    if (init?.method === "GET") {
      const q = url.searchParams.get("q") ?? "";
      const offset = Number(url.searchParams.get("offset")), limit = Number(url.searchParams.get("limit"));
      const items = catalogue.filter(item => item.name.toLowerCase().includes(q.toLowerCase())).slice(offset, offset + limit);
      return new Response(JSON.stringify({ items, limit, offset }));
    }
    if (init?.method === "POST") {
      const created = { ...skill(`new-${catalogue.length}`, body.name), description: body.description };
      catalogue.unshift(created);
      return new Response(JSON.stringify(created), { status: 201 });
    }
    const id = decodeURIComponent(url.pathname.split("/").pop()!);
    const index = catalogue.findIndex(item => item.id === id);
    catalogue[index] = { ...catalogue[index], ...body, updatedAt: "2026-11-18T09:00:00Z" };
    return new Response(JSON.stringify(catalogue[index]));
  });
  return {
    api: new AdminApi(async () => "admin-token", fetchImpl, "http://gateway.test"),
    calls,
    failWith: (status: number, detail: string) => { failNext = { status, detail }; },
  };
}

it("lists the full catalogue, including disabled skills, with the admin token", async () => {
  const backend = fakeBackend([skill("1", "Dribble"), skill("2", "Tir", false)]);
  render(<SkillsPage api={backend.api} />);
  expect(await screen.findByText("Dribble")).toBeInTheDocument();
  expect(screen.getByText("Désactivée")).toBeInTheDocument();
  expect(backend.calls[0].url.pathname).toBe("/v1/skills/admin");
  expect(backend.calls[0].url.searchParams.get("limit")).toBe("21");
  expect(backend.calls[0].auth).toBe("Bearer admin-token");
});

it("creates a skill with trimmed fields and shows it", async () => {
  const backend = fakeBackend([]);
  const user = userEvent.setup();
  render(<SkillsPage api={backend.api} />);
  await screen.findByText("Le catalogue est vide.");
  await user.type(screen.getByLabelText("Nom"), "  Contrôle orienté ");
  await user.type(screen.getByLabelText(/Description/), " Premier contact ");
  await user.click(screen.getByRole("button", { name: "Ajouter" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Compétence « Contrôle orienté » ajoutée.");
  expect(backend.calls.find(call => call.method === "POST")?.body).toEqual({ name: "Contrôle orienté", description: "Premier contact" });
  expect(await screen.findByRole("cell", { name: "Contrôle orienté" })).toBeInTheDocument();
});

it("validates locally and shows the server's conflict message", async () => {
  const backend = fakeBackend([]);
  const user = userEvent.setup();
  render(<SkillsPage api={backend.api} />);
  await screen.findByText("Le catalogue est vide.");
  await user.type(screen.getByLabelText("Nom"), "x".repeat(81));
  await user.click(screen.getByRole("button", { name: "Ajouter" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Le nom dépasse 80 caractères.");
  expect(backend.calls.some(call => call.method === "POST")).toBe(false);

  await user.clear(screen.getByLabelText("Nom"));
  await user.type(screen.getByLabelText("Nom"), "Dribble");
  backend.failWith(409, "Une compétence porte déjà ce nom canonique.");
  await user.click(screen.getByRole("button", { name: "Ajouter" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Une compétence porte déjà ce nom canonique.");
});

it("edits only the changed field, then deactivates and reactivates", async () => {
  const backend = fakeBackend([skill("1", "Dribble")]);
  const user = userEvent.setup();
  render(<SkillsPage api={backend.api} />);
  const row = (await screen.findByText("Dribble")).closest("tr")!;
  await user.click(within(row).getByRole("button", { name: "Modifier" }));
  const name = screen.getByLabelText("Nouveau nom");
  await user.clear(name);
  await user.type(name, "Dribble court");
  await user.click(screen.getByRole("button", { name: "Enregistrer" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Compétence « Dribble court » modifiée.");
  expect(backend.calls.find(call => call.method === "PATCH")?.body).toEqual({ name: "Dribble court" });

  await user.click(screen.getByRole("button", { name: "Désactiver Dribble court" }));
  expect(await screen.findByText("Désactivée")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Réactiver Dribble court" }));
  await waitFor(() => expect(screen.getByText("Active")).toBeInTheDocument());
  expect(backend.calls.filter(call => call.method === "PATCH").map(call => call.body))
    .toEqual([{ name: "Dribble court" }, { active: false }, { active: true }]);
});

it("searches from the first page and pages through results", async () => {
  const many = Array.from({ length: 25 }, (_, n) => skill(String(n), `Skill ${String(n).padStart(2, "0")}`));
  const backend = fakeBackend(many);
  const user = userEvent.setup();
  render(<SkillsPage api={backend.api} />);
  await screen.findByText("Skill 00");
  await user.click(screen.getByRole("button", { name: "Suivantes" }));
  expect(await screen.findByText("Skill 20")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Suivantes" })).toBeDisabled();

  await user.type(screen.getByLabelText("Rechercher une compétence"), "Skill 2");
  await user.click(screen.getByRole("button", { name: "Rechercher" }));
  await screen.findByText("Skill 24");
  expect(screen.queryByText("Skill 02")).not.toBeInTheDocument();
  const last = backend.calls.at(-1)!;
  expect(last.url.searchParams.get("q")).toBe("Skill 2");
  expect(last.url.searchParams.get("offset")).toBe("0");
});

it("shows a readable message when the Gateway refuses the role", async () => {
  const backend = fakeBackend([]);
  backend.failWith(403, "ignored");
  render(<SkillsPage api={backend.api} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Ce compte n'a pas le rôle admin.");
});
