import { afterEach, expect, it, vi } from "vitest";
import { AdminApi } from "./api";
import { Session } from "./auth";

/** Comme le `fetch` du navigateur : refuse d'être appelé comme méthode d'un autre objet. */
function strictFetch(body: unknown) {
  return vi.fn(function (this: unknown, _input: RequestInfo | URL, _init?: RequestInit) {
    if (this !== undefined && this !== globalThis) throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation");
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
  });
}

afterEach(() => vi.unstubAllGlobals());

it("AdminApi uses the browser fetch without a foreign receiver", async () => {
  const fetch = strictFetch({ videos: 1, published: 1, removed: 0 });
  vi.stubGlobal("fetch", fetch);
  await expect(new AdminApi(async () => "token").videoTotals()).resolves.toEqual({ videos: 1, published: 1, removed: 0 });
  expect(fetch.mock.results).toEqual([expect.objectContaining({ type: "return" })]);
});

it("Session logout reaches Keycloak with the browser fetch", async () => {
  const fetch = strictFetch({});
  vi.stubGlobal("fetch", fetch);
  await new Session({ access_token: "a.e30.c", refresh_token: "r", expires_in: 300 } as never).logout();
  expect(fetch.mock.results).toEqual([expect.objectContaining({ type: "return" })]);
  expect(String(fetch.mock.calls[0][0])).toContain("/logout");
});
