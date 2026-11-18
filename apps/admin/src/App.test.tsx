import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App, AppDeps } from "./App";
import { AuthError, Session } from "./auth";

const token = (roles: string[]) => `h.${btoa(JSON.stringify({ realm_access: { roles }, preferred_username: "coach" })).replace(/=+$/, "")}.s`;
const session = (roles: string[]) => new Session({ access_token: token(roles), refresh_token: "r", expires_in: 300 },
  vi.fn(async () => new Response(JSON.stringify({ items: [], limit: 21, offset: 0 }))));

function deps(path: string, completeLogin: AppDeps["completeLogin"] = vi.fn()): AppDeps & { assign: ReturnType<typeof vi.fn>; replaceUrl: ReturnType<typeof vi.fn> } {
  const assign = vi.fn();
  const replaceUrl = vi.fn();
  const url = new URL(path, "http://localhost:7358");
  return {
    location: { href: url.href, origin: url.origin, pathname: url.pathname, assign },
    replaceUrl, assign, completeLogin,
    loginUrl: vi.fn(async () => "http://keycloak.test/auth?client_id=youscout-admin"),
  };
}

it("starts the Keycloak login from the signed-out screen", async () => {
  const d = deps("/");
  render(<App deps={d} />);
  await userEvent.click(await screen.findByRole("button", { name: "Se connecter avec Keycloak" }));
  expect(d.assign).toHaveBeenCalledWith("http://keycloak.test/auth?client_id=youscout-admin");
});

it("removes the code from the address bar and opens the catalogue for an admin", async () => {
  const d = deps("/callback?code=abc&state=xyz", vi.fn(async () => session(["user", "admin"])));
  render(<App deps={d} />);
  expect(await screen.findByText("Ajouter une compétence")).toBeInTheDocument();
  expect(d.replaceUrl).toHaveBeenCalledWith("/");
  expect(screen.getByText("coach")).toBeInTheDocument();
});

it("refuses access to an account without the admin role", async () => {
  render(<App deps={deps("/callback?code=abc&state=xyz", vi.fn(async () => session(["user"])))} />);
  expect(await screen.findByText("Accès refusé")).toBeInTheDocument();
  expect(screen.queryByText("Ajouter une compétence")).not.toBeInTheDocument();
});

it("shows the login error and lets the user try again", async () => {
  render(<App deps={deps("/callback?state=forged", vi.fn(async () => { throw new AuthError("Réponse de connexion invalide. Recommence."); }))} />);
  expect(await screen.findByRole("alert")).toHaveTextContent("Réponse de connexion invalide.");
  expect(screen.getByRole("button", { name: "Se connecter avec Keycloak" })).toBeInTheDocument();
});

it("signs out through Keycloak and returns to the login screen", async () => {
  const signedIn = session(["admin"]);
  const logout = vi.spyOn(signedIn, "logout").mockResolvedValue();
  render(<App deps={deps("/callback?code=abc&state=xyz", vi.fn(async () => signedIn))} />);
  await userEvent.click(await screen.findByRole("button", { name: "Se déconnecter" }));
  expect(logout).toHaveBeenCalled();
  expect(await screen.findByRole("button", { name: "Se connecter avec Keycloak" })).toBeInTheDocument();
});

it("lets an admin switch between the catalogue and the moderation queue", async () => {
  render(<App deps={deps("/callback?code=abc&state=xyz", vi.fn(async () => session(["admin"])))} />);
  await screen.findByText("Ajouter une compétence");
  await userEvent.click(screen.getByRole("button", { name: "Signalements" }));
  expect(await screen.findByRole("heading", { name: "Signalements" })).toBeInTheDocument();
  expect(screen.queryByText("Ajouter une compétence")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Compétences" }));
  expect(await screen.findByText("Ajouter une compétence")).toBeInTheDocument();
});
