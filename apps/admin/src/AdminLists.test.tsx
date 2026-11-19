import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminApi } from "./api";
import { ContentPage } from "./ContentPage";
import { DashboardPage } from "./DashboardPage";
import { UsersPage } from "./UsersPage";

type Route = (url: URL) => unknown;

function backend(routes: Record<string, Route>, failing: string[] = []) {
  const calls: string[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input));
    calls.push(url.pathname + url.search);
    if (failing.includes(url.pathname)) return new Response(JSON.stringify({ detail: "Service arrêté." }), { status: 503 });
    const route = routes[url.pathname];
    if (!route) return new Response("{}", { status: 404 });
    return new Response(JSON.stringify(route(url)));
  });
  return { api: new AdminApi(async () => "admin-token", fetchImpl, "http://gateway.test"), calls };
}

const account = (subject: string, displayName: string, status = "ACTIVE") =>
  ({ subject, displayName, status, statusChangedAt: null, statusChangedBy: null, statusNote: null, createdAt: "2026-11-01T10:00:00Z" });

it("lists every account, filters them and opens one", async () => {
  const { api, calls } = backend({
    "/v1/users/admin/accounts": url => url.searchParams.get("status") === "CLOSED"
      ? [account("p-3", "Compte fermé", "CLOSED")] : [account("p-1", "Yassine"), account("p-2", "Salma", "DEACTIVATED")],
  });
  const opened = vi.fn();
  const user = userEvent.setup();
  render(<UsersPage api={api} onOpenAccount={opened} />);
  expect(await screen.findByText("Yassine")).toBeInTheDocument();
  expect(within(screen.getByRole("table")).getByText("Désactivé")).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("Statut"), "CLOSED");
  await user.type(screen.getByLabelText("Nom ou identifiant"), "  ferm ");
  await user.click(screen.getByRole("button", { name: "Filtrer" }));
  expect(await screen.findByText("Compte fermé")).toBeInTheDocument();
  expect(calls.at(-1)).toBe("/v1/users/admin/accounts?q=ferm&status=CLOSED&limit=50");
  await user.click(screen.getByRole("button", { name: "Ouvrir" }));
  expect(opened).toHaveBeenCalledWith("p-3");
});

it("shows videos and comments with their authors' names", async () => {
  const { api, calls } = backend({
    "/v1/videos/admin": () => [{ id: "v-1", title: "Dribble", ownerSubject: "p-1", processingStatus: "READY", publicationStatus: "PUBLISHED",
      moderationStatus: "REMOVED", createdAt: "2026-11-02T10:00:00Z", publishedAt: "2026-11-02T11:00:00Z" }],
    "/v1/social/admin/comments": () => [
      { id: "c-1", videoId: "v-1", parentId: null, authorSubject: "p-2", body: "Bravo", createdAt: "2026-11-03T10:00:00Z", deleted: false, removedByModeration: false },
      { id: "c-2", videoId: "v-1", parentId: "c-1", authorSubject: "gone", body: "", createdAt: "2026-11-03T11:00:00Z", deleted: true, removedByModeration: false }],
    "/v1/users/admin/accounts/names": () => ({ "p-1": "Yassine", "p-2": "Salma" }),
  });
  const user = userEvent.setup();
  render(<ContentPage api={api} onOpenAccount={vi.fn()} />);
  expect(await screen.findByText("Dribble")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Yassine" })).toBeInTheDocument();
  expect(within(screen.getByRole("table")).getByText(/Retirée/)).toBeInTheDocument();
  await user.click(screen.getByRole("tab", { name: "Commentaires" }));
  expect(await screen.findByText("Bravo")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Salma" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Compte supprimé" })).toBeInTheDocument();
  expect(screen.getByText("(texte effacé)")).toBeInTheDocument();
  expect(calls).toContain("/v1/users/admin/accounts/names?subject=p-2&subject=gone");
});

it("shows totals and activity, and keeps the rest when one source fails", async () => {
  const { api } = backend({
    "/v1/users/admin/accounts/stats": () => ({ accounts: { ACTIVE: 12, DEACTIVATED: 2, CLOSING: 1, CLOSED: 3 } }),
    "/v1/videos/admin/stats": () => ({ videos: 9, published: 7, removed: 1 }),
    "/v1/social/admin/stats": () => ({ comments: 40, likes: 120, openReports: 4 }),
    "/v1/analytics/activity": () => ({ from: "2026-11-18", to: "2026-11-19", coverageStart: "2026-11-18", days: [
      { day: "2026-11-18", newAccounts: 2, videosPublished: 1, likes: 5, comments: 3, messages: 7 },
      { day: "2026-11-19", newAccounts: 1, videosPublished: 0, likes: 4, comments: 0, messages: 2 }] }),
  }, ["/v1/ratings/admin/stats"]);
  render(<DashboardPage api={api} />);
  const totals = within(await screen.findByRole("region", { name: "Totaux" }));
  expect(await totals.findByText("15")).toBeInTheDocument();
  expect(totals.getByText("12 actifs · 2 désactivés · 4 fermés ou en fermeture")).toBeInTheDocument();
  expect(totals.getByText("7")).toBeInTheDocument();
  expect(totals.getByText("4")).toBeInTheDocument();
  expect(await totals.findByText("Indisponible : Service arrêté.")).toBeInTheDocument();
  const activity = within(screen.getByRole("region", { name: "Activité" }));
  const captions = (await activity.findAllByRole("figure")).map(figure => figure.querySelector("figcaption")?.textContent);
  expect(captions).toEqual(["3 nouveaux comptes", "1 vidéos publiées", "9 likes", "3 commentaires", "9 messages"]);
  expect(activity.getByText(/Comptage depuis le 18 nov/)).toBeInTheDocument();
});
