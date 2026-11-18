import { config } from "./auth";

export type Skill = {
  id: string;
  name: string;
  description: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};
export type SkillPatch = Partial<Pick<Skill, "name" | "description" | "active">>;
export type SkillPage = { items: Skill[]; hasMore: boolean };

export type ReportType = "VIDEO" | "COMMENT";
export type ReportStatus = "OPEN" | "REVIEWED" | "DISMISSED";
export type Report = { reporterSubject: string; reason: string; createdAt: string };
export type ReportTarget = {
  type: ReportType;
  targetId: string;
  videoId: string;
  commentBody: string | null;
  commentAuthor: string | null;
  commentRemoved: boolean;
  reportCount: number;
  firstReportedAt: string;
  lastReportedAt: string;
  reports: Report[];
};
export type ReportPage = { items: ReportTarget[]; hasMore: boolean };
export type AdminVideo = {
  id: string;
  title: string;
  ownerSubject: string;
  publicationStatus: string;
  moderationStatus: "ACTIVE" | "REMOVED";
  moderatedBy: string | null;
  moderationNote: string | null;
};
export type Playback = { videoUrl: string; thumbnailUrl: string; expiresAt: string };

export const PAGE_SIZE = 20;
export const NAME_MAX = 80;
export const DESCRIPTION_MAX = 500;

export class ApiError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

type Fetch = typeof fetch;

/** Appels au catalogue Skill via la Gateway ; seuls les endpoints réservés au rôle admin sont utilisés. */
export class AdminApi {
  constructor(private readonly token: () => Promise<string>, private readonly fetchImpl: Fetch = fetch,
              private readonly base: string = config.api) {}

  async listSkills(q: string, offset: number): Promise<SkillPage> {
    // Une ligne de plus que la page indique s'il existe une page suivante.
    const params = new URLSearchParams({ q, limit: String(PAGE_SIZE + 1), offset: String(offset) });
    const page = await this.request<{ items: Skill[] }>("GET", `/v1/skills/admin?${params}`);
    return { items: page.items.slice(0, PAGE_SIZE), hasMore: page.items.length > PAGE_SIZE };
  }

  createSkill(name: string, description: string): Promise<Skill> {
    return this.request("POST", "/v1/skills/admin", { name, description });
  }

  updateSkill(id: string, patch: SkillPatch): Promise<Skill> {
    return this.request("PATCH", `/v1/skills/admin/${encodeURIComponent(id)}`, patch);
  }

  // Modération : file de Social, actions sur les vidéos dans Video.
  listReports(type: ReportType, status: ReportStatus, offset: number): Promise<ReportPage> {
    const params = new URLSearchParams({ type, status, offset: String(offset) });
    return this.request("GET", `/v1/social/admin/reports?${params}`);
  }

  decide(type: ReportType, targetId: string, decision: "DISMISSED" | "ACTION_TAKEN", note: string): Promise<unknown> {
    return this.request("POST", `/v1/social/admin/reports/${type}/${encodeURIComponent(targetId)}/decision`, withNote({ decision }, note));
  }

  removeComment(id: string, note: string): Promise<unknown> {
    return this.request("POST", `/v1/social/admin/comments/${encodeURIComponent(id)}/remove`, withNote({}, note));
  }

  adminVideo(id: string): Promise<AdminVideo> {
    return this.request("GET", `/v1/videos/admin/${encodeURIComponent(id)}`);
  }

  adminPlayback(id: string): Promise<Playback> {
    return this.request("GET", `/v1/videos/admin/${encodeURIComponent(id)}/playback`);
  }

  takeDown(id: string, note: string): Promise<AdminVideo> {
    return this.request("POST", `/v1/videos/admin/${encodeURIComponent(id)}/takedown`, withNote({}, note));
  }

  restore(id: string, note: string): Promise<AdminVideo> {
    return this.request("POST", `/v1/videos/admin/${encodeURIComponent(id)}/restore`, withNote({}, note));
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${await this.token()}` };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    let response: Response;
    try {
      response = await this.fetchImpl(this.base + path, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store",
      });
    } catch {
      throw new ApiError(0, "La Gateway est injoignable. Vérifie que le backend local est démarré.");
    }
    if (!response.ok) throw new ApiError(response.status, await problem(response));
    return (await response.json()) as T;
  }
}

async function problem(response: Response): Promise<string> {
  if (response.status === 401) return "Session expirée. Reconnecte-toi.";
  if (response.status === 403) return "Ce compte n'a pas le rôle admin.";
  try {
    const data = (await response.json()) as { detail?: unknown };
    if (typeof data.detail === "string" && data.detail.trim()) return data.detail;
  } catch { /* Corps absent ou non JSON. */ }
  return response.status >= 500 ? "Le catalogue est momentanément indisponible." : `Requête refusée (${response.status}).`;
}

/** Les limites du serveur portent sur les points de code Unicode, pas sur les unités UTF-16. */
export const codePoints = (text: string) => [...text].length;

function withNote(body: Record<string, string>, note: string): Record<string, string> {
  return note.trim() ? { ...body, note: note.trim() } : body;
}
