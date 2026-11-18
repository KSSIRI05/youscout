import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminApi, Hashtag } from "./api";
import { HashtagsPage } from "./HashtagsPage";

const tag = (id: string, name: string, active = true): Hashtag =>
  ({ id, name, active, createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:00:00Z" });

function backend(initial: Hashtag[]) {
  const tags = [...initial];
  const calls: { method: string; url: URL; body?: unknown }[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init?.method ?? "GET", url, body });
    if (init?.method === "PATCH") {
      const target = tags.find(item => url.pathname.endsWith(item.id))!;
      target.active = body.active;
      return new Response(JSON.stringify(target));
    }
    const q = url.searchParams.get("q") ?? "";
    return new Response(JSON.stringify({ items: tags.filter(item => item.name.includes(q)) }));
  });
  return { api: new AdminApi(async () => "admin-token", fetchImpl, "http://gateway.test"), calls };
}

it("lists every hashtag, including deactivated ones, from the admin endpoint", async () => {
  const { api, calls } = backend([tag("1", "football"), tag("2", "violent", false)]);
  render(<HashtagsPage api={api} />);
  expect(await screen.findByText("#football")).toBeInTheDocument();
  expect(screen.getByText("Désactivé")).toBeInTheDocument();
  expect(calls[0].url.pathname).toBe("/v1/hashtags/admin");
});

it("deactivates and reactivates a hashtag", async () => {
  const { api, calls } = backend([tag("1", "violent")]);
  const user = userEvent.setup();
  render(<HashtagsPage api={api} />);
  await user.click(await screen.findByRole("button", { name: "Désactiver #violent" }));
  expect(await screen.findByRole("status")).toHaveTextContent("#violent désactivé");
  await user.click(screen.getByRole("button", { name: "Réactiver #violent" }));
  expect(await screen.findByRole("status")).toHaveTextContent("#violent réactivé");
  expect(calls.filter(call => call.method === "PATCH").map(call => call.body)).toEqual([{ active: false }, { active: true }]);
});

it("searches from the first page", async () => {
  const { api, calls } = backend([tag("1", "football"), tag("2", "futsal")]);
  const user = userEvent.setup();
  render(<HashtagsPage api={api} />);
  await screen.findByText("#football");
  await user.type(screen.getByLabelText("Rechercher un hashtag"), "fut");
  await user.click(screen.getByRole("button", { name: "Rechercher" }));
  expect(await screen.findByText("#futsal")).toBeInTheDocument();
  expect(screen.queryByText("#football")).not.toBeInTheDocument();
  expect(calls.at(-1)!.url.searchParams.get("offset")).toBe("0");
});
