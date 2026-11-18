import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminApi, AdminVideo, ReportTarget } from "./api";
import { ModerationPage } from "./ModerationPage";

const report = (reason: string, reporter: string) => ({ reason, reporterSubject: reporter, createdAt: "2026-11-18T10:00:00Z" });
const videoTarget = (id: string, count = 2): ReportTarget => ({
  type: "VIDEO", targetId: id, videoId: id, commentBody: null, commentAuthor: null, commentRemoved: false,
  reportCount: count, firstReportedAt: "2026-11-18T09:00:00Z", lastReportedAt: "2026-11-18T10:00:00Z",
  reports: [report("Violence", "alice"), report("Choquant", "bob")],
});
const commentTarget = (id: string, videoId: string): ReportTarget => ({
  type: "COMMENT", targetId: id, videoId, commentBody: "Va-t'en", commentAuthor: "carol", commentRemoved: false,
  reportCount: 1, firstReportedAt: "2026-11-18T09:00:00Z", lastReportedAt: "2026-11-18T09:00:00Z", reports: [report("Harcèlement", "dave")],
});

/** Fausse Gateway : file Social par type/état, vidéos Video et journal des appels. */
function backend() {
  const queues: Record<string, ReportTarget[]> = {
    "VIDEO:OPEN": [videoTarget("v1")], "COMMENT:OPEN": [commentTarget("c1", "v1")],
    "VIDEO:REVIEWED": [], "VIDEO:DISMISSED": [], "COMMENT:REVIEWED": [], "COMMENT:DISMISSED": [],
  };
  const videos: Record<string, AdminVideo> = {
    v1: { id: "v1", title: "But de la tête", ownerSubject: "player-9", publicationStatus: "PUBLISHED",
          moderationStatus: "ACTIVE", moderatedBy: null, moderationNote: null },
  };
  const calls: { method: string; path: string; body?: Record<string, unknown> }[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init?.method ?? "GET", path: url.pathname, body });
    const parts = url.pathname.split("/");
    if (url.pathname === "/v1/social/admin/reports")
      return json({ items: queues[`${url.searchParams.get("type")}:${url.searchParams.get("status")}`], hasMore: false });
    if (url.pathname.startsWith("/v1/videos/admin/")) {
      const video = videos[parts[4]];
      if (!video) return json({ detail: "Vidéo introuvable." }, 404);
      if (parts[5] === "playback") return json({ videoUrl: "https://s3.test/v1?sig", thumbnailUrl: "https://s3.test/t", expiresAt: "2026-11-18T11:00:00Z" });
      if (parts[5] === "takedown") Object.assign(video, { moderationStatus: "REMOVED", moderationNote: body?.note ?? null });
      if (parts[5] === "restore") Object.assign(video, { moderationStatus: "ACTIVE" });
      return json(video);
    }
    if (url.pathname.endsWith("/decision")) {
      const [type, id] = [parts[5], parts[6]];
      const open = queues[`${type}:OPEN`];
      const target = open.find(item => item.targetId === id)!;
      queues[`${type}:OPEN`] = open.filter(item => item !== target);
      queues[`${type}:${body!.decision === "DISMISSED" ? "DISMISSED" : "REVIEWED"}`].push(target);
      return json({ decision: body!.decision, reportsClosed: target.reportCount });
    }
    if (url.pathname.endsWith("/remove")) {
      const target = queues["COMMENT:OPEN"].find(item => item.targetId === parts[5])!;
      queues["COMMENT:OPEN"] = [];
      queues["COMMENT:REVIEWED"].push({ ...target, commentRemoved: true });
      return json({ decision: "COMMENT_REMOVED", reportsClosed: 1 });
    }
    return json({}, 404);
  });
  return { api: new AdminApi(async () => "admin-token", fetchImpl, "http://gateway.test"), calls, videos };
}

it("shows reported videos with their title, owner, count and reasons", async () => {
  const { api } = backend();
  render(<ModerationPage api={api} open={vi.fn()} />);
  expect(await screen.findByText("But de la tête")).toBeInTheDocument();
  expect(screen.getByText("Propriétaire : player-9")).toBeInTheDocument();
  expect(screen.getByText("2 signalements")).toBeInTheDocument();
  expect(screen.getByText("Violence")).toBeInTheDocument();
});

it("takes a video down with a note, closes its reports, and can restore it", async () => {
  const { api, calls, videos } = backend();
  const user = userEvent.setup();
  render(<ModerationPage api={api} open={vi.fn()} />);
  await screen.findByText("But de la tête");
  await user.type(screen.getByLabelText("Note pour v1"), "Violence explicite");
  await user.click(screen.getByRole("button", { name: "Retirer la vidéo" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Vidéo « But de la tête » retirée");
  expect(videos.v1.moderationStatus).toBe("REMOVED");
  expect(calls.filter(call => call.method === "POST").map(call => [call.path, call.body])).toEqual([
    ["/v1/videos/admin/v1/takedown", { note: "Violence explicite" }],
    ["/v1/social/admin/reports/VIDEO/v1/decision", { decision: "ACTION_TAKEN", note: "Violence explicite" }],
  ]);
  expect(screen.getByText("Aucun signalement dans cette catégorie.")).toBeInTheDocument();

  await user.selectOptions(screen.getByLabelText("État"), "REVIEWED");
  expect(await screen.findByText("Vidéo retirée")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Rétablir" }));
  expect(await screen.findByRole("status")).toHaveTextContent("rétablie");
  expect(videos.v1.moderationStatus).toBe("ACTIVE");
});

it("dismisses reports without touching the video", async () => {
  const { api, calls, videos } = backend();
  const user = userEvent.setup();
  render(<ModerationPage api={api} open={vi.fn()} />);
  await screen.findByText("But de la tête");
  await user.click(screen.getByRole("button", { name: "Classer sans suite" }));
  expect(await screen.findByRole("status")).toHaveTextContent("classés sans suite");
  expect(calls.find(call => call.path.endsWith("/decision"))?.body).toEqual({ decision: "DISMISSED" });
  expect(calls.some(call => call.path.endsWith("/takedown"))).toBe(false);
  expect(videos.v1.moderationStatus).toBe("ACTIVE");
});

it("removes a reported comment from the Comments tab", async () => {
  const { api, calls } = backend();
  const user = userEvent.setup();
  render(<ModerationPage api={api} open={vi.fn()} />);
  await screen.findByText("But de la tête");
  await user.click(screen.getByRole("tab", { name: "Commentaires" }));
  const item = (await screen.findByText("« Va-t'en »")).closest("article")!;
  expect(within(item).getByText("Auteur : carol · sur « But de la tête »")).toBeInTheDocument();
  await user.click(within(item).getByRole("button", { name: "Retirer le commentaire" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Commentaire retiré");
  expect(calls.find(call => call.path.endsWith("/remove"))).toMatchObject({ path: "/v1/social/admin/comments/c1/remove", body: {} });
});

it("opens the review playback link and shows errors from the API", async () => {
  const { api, videos } = backend();
  const open = vi.fn();
  const user = userEvent.setup();
  render(<ModerationPage api={api} open={open} />);
  await screen.findByText("But de la tête");
  await user.click(screen.getByRole("button", { name: "Regarder" }));
  expect(open).toHaveBeenCalledWith("https://s3.test/v1?sig");

  delete (videos as Record<string, AdminVideo>).v1;
  await user.click(screen.getByRole("button", { name: "Regarder" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Vidéo introuvable.");
});
