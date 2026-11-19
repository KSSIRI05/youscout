import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Account, AdminApi } from "./api";
import { AccountsPage } from "./AccountsPage";

function backend() {
  const accounts: Record<string, Account> = {
    "player-1": { subject: "player-1", displayName: "Yassine", status: "ACTIVE", statusChangedAt: null, statusChangedBy: null,
                  statusNote: null, createdAt: "2026-10-20T10:00:00Z" },
    "player-2": { subject: "player-2", displayName: "Compte fermé", status: "CLOSING", statusChangedAt: "2026-11-18T12:00:00Z",
                  statusChangedBy: "player-2", statusNote: null, createdAt: "2026-10-20T10:00:00Z" },
  };
  const closure = { subject: "player-2", status: "IN_PROGRESS", requestedAt: "2026-11-18T12:00:00Z", completedAt: null, steps: [
    { name: "SESSIONS", status: "DONE", attempts: 1, lastError: null, completedAt: "2026-11-18T12:00:00Z" },
    { name: "CHAT", status: "PENDING", attempts: 3, lastError: "HTTP 503", completedAt: null },
  ] };
  const calls: { method: string; path: string; body?: unknown }[] = [];
  let closureDown = false;
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init?.method ?? "GET", path: url.pathname, body });
    const [, , , , , subject, action] = url.pathname.split("/");
    const account = accounts[decodeURIComponent(subject)];
    if (!account) return new Response(JSON.stringify({ detail: "Profil introuvable." }), { status: 404 });
    if (action === "closure") return closureDown ? new Response(JSON.stringify({ detail: "Service indisponible." }), { status: 503 })
                                                 : new Response(JSON.stringify(closure));
    if (action) Object.assign(account, { status: action === "deactivate" ? "DEACTIVATED" : "ACTIVE",
      statusChangedAt: "2026-11-18T12:00:00Z", statusChangedBy: "moderator", statusNote: body?.note ?? null });
    return new Response(JSON.stringify(account));
  });
  return { api: new AdminApi(async () => "admin-token", fetchImpl, "http://gateway.test"), calls,
           breakClosure: () => { closureDown = true; } };
}

it("looks up an account and requires confirmation before deactivating it", async () => {
  const { api, calls } = backend();
  const user = userEvent.setup();
  render(<AccountsPage api={api} />);
  await user.type(screen.getByLabelText("Identifiant du compte"), "player-1");
  await user.click(screen.getByRole("button", { name: "Afficher" }));
  expect(await screen.findByText("Yassine")).toBeInTheDocument();
  expect(screen.getByText("Actif")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Désactiver le compte" }));
  expect(calls.some(call => call.method === "POST")).toBe(false);
  await user.type(screen.getByLabelText("Note"), "Harcèlement répété");
  await user.click(screen.getByRole("button", { name: "Confirmer la désactivation" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Compte de Yassine désactivé");
  expect(screen.getByText("Désactivé")).toBeInTheDocument();
  expect(calls.find(call => call.method === "POST")).toMatchObject({
    path: "/v1/users/admin/accounts/player-1/deactivate", body: { note: "Harcèlement répété" } });

  await user.click(screen.getByRole("button", { name: "Réactiver le compte" }));
  expect(await screen.findByRole("status")).toHaveTextContent("réactivé");
});

it("opens directly on a subject passed from the report queue and shows lookup errors", async () => {
  const { api } = backend();
  render(<AccountsPage api={api} initialSubject="player-1" />);
  expect(await screen.findByText("Yassine")).toBeInTheDocument();

  const user = userEvent.setup();
  await user.clear(screen.getByLabelText("Identifiant du compte"));
  await user.type(screen.getByLabelText("Identifiant du compte"), "ghost");
  await user.click(screen.getByRole("button", { name: "Afficher" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Profil introuvable.");
  expect(screen.queryByText("Yassine")).not.toBeInTheDocument();
});

it("shows the closure log of an account closed by its owner, without deactivation actions", async () => {
  const { api, calls } = backend();
  render(<AccountsPage api={api} initialSubject="player-2" />);
  expect(await screen.findByText("Fermeture en cours")).toBeInTheDocument();
  expect(await screen.findByText(/Connexion bloquée et sessions fermées : fait/)).toBeInTheDocument();
  expect(screen.getByText(/Texte des messages effacé : en attente \(3 essais, HTTP 503\)/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /activer le compte/ })).not.toBeInTheDocument();
  expect(calls.map(call => call.path)).toContain("/v1/users/admin/accounts/player-2/closure");
});

it("keeps a closing account visible when its closure log cannot be read", async () => {
  const { api, breakClosure } = backend();
  breakClosure();
  render(<AccountsPage api={api} initialSubject="player-2" />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Journal de fermeture indisponible : Service indisponible.");
  expect(screen.getByText("Compte fermé")).toBeInTheDocument();
  expect(screen.getByText("Fermeture en cours")).toBeInTheDocument();
});
