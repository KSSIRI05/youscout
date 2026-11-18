"""Real Keycloak, Gateway, User and Feed check of account deactivation (A04). Temporary accounts are removed.

Two temporary users log in through the real Keycloak form (client youscout-admin). The admin deactivates the
player through the Gateway: Keycloak disables the account and ends its sessions, the profile disappears, and
Feed hides the account's videos. Reactivation restores everything. No password or token is printed.
"""
import importlib.util
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from keycloak_admin import KeycloakAdmin, ROOT

spec = importlib.util.spec_from_file_location("smoke_identity", ROOT / "scripts" / "smoke-identity.py")
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)
identity.CALLBACK = "http://localhost:7358/callback"  # The browser stops at the admin console callback.
API = "http://localhost:8080"
REALM = "realms/youscout"
COMPOSE = ["docker", "compose", "-f", str(ROOT / "infra/compose/compose.yml"), "-f", str(ROOT / "infra/compose/compose.kafka.yml"),
           "--profile", "video", "--profile", "feed"]


def login(username, password):
    """Authorization Code + PKCE through the real Keycloak login form; returns the token response or None if refused."""
    verifier, proof = identity.challenge()
    state = secrets.token_urlsafe(24)
    browser = identity.BrowserSession()
    status, html, _ = browser.fetch(identity.ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode({
        "client_id": "youscout-admin", "response_type": "code", "scope": "openid", "redirect_uri": identity.CALLBACK,
        "code_challenge": proof, "code_challenge_method": "S256", "state": state}))
    assert status == 200, "Keycloak login page unavailable"
    form = identity.Page(html).form("kc-form-login")
    status, _, headers = browser.fetch(form["action"], {**form["hidden"], "username": username, "password": password})
    location = headers.get("Location", "")
    if not location.startswith(identity.CALLBACK + "?"):
        return None
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    assert params.get("state") == [state], "OIDC state mismatch"
    status, tokens = identity.token_request({"grant_type": "authorization_code", "client_id": "youscout-admin",
                                             "redirect_uri": identity.CALLBACK, "code": params["code"][0], "code_verifier": verifier})
    assert status == 200, "Code exchange failed"
    return tokens


def call(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": "Bearer " + token, **({"Content-Type": "application/json"} if data else {})}
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, data=data, headers=headers, method=method), timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


def deactivated_in_feed(subject):
    output = subprocess.run([*COMPOSE, "exec", "-T", "feed-redis", "redis-cli", "SISMEMBER",
                             "youscout:feed:relations:deactivated-accounts", subject], capture_output=True, text=True, timeout=30)
    return output.stdout.strip() == "1"


def wait_for(predicate, message, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError(message)


def create_user(admin, name, password, role=None):
    admin.request("POST", REALM + "/users", {"username": name, "enabled": True, "emailVerified": True, "email": name + "@example.invalid",
        "firstName": "Account", "lastName": "Check", "credentials": [{"type": "password", "value": password, "temporary": False}]})
    user_id = admin.request("GET", REALM + "/users?exact=true&username=" + name)[0]["id"]
    if role:
        admin.request("POST", f"{REALM}/users/{user_id}/role-mappings/realm", [admin.request("GET", REALM + "/roles/" + role)])
    return user_id


def main():
    admin = KeycloakAdmin()
    password = "Aa1!" + secrets.token_urlsafe(24)
    names = {"player": "account-player-" + secrets.token_hex(4), "moderator": "account-admin-" + secrets.token_hex(4)}
    ids = {}
    try:
        ids["player"] = create_user(admin, names["player"], password)
        ids["moderator"] = create_user(admin, names["moderator"], password, "admin")
        player = login(names["player"], password)
        moderator = login(names["moderator"], password)["access_token"]
        assert player and call("GET", "/v1/users/me", player["access_token"])[0] == 200
        assert call("GET", "/v1/users/me", moderator)[0] == 200
        target = ids["player"]
        assert call("POST", f"/v1/users/admin/accounts/{target}/deactivate", player["access_token"])[0] == 403, "Player reached account administration"

        status, account = call("POST", f"/v1/users/admin/accounts/{target}/deactivate", moderator, {"note": "Smoke check"})
        assert status == 200 and account["status"] == "DEACTIVATED", f"Deactivation failed ({status}); check the youscout-user-admin client"
        assert admin.request("GET", f"{REALM}/users/{target}")["enabled"] is False, "Keycloak account still enabled"
        assert identity.token_request({"grant_type": "refresh_token", "client_id": "youscout-admin",
                                       "refresh_token": player["refresh_token"]})[0] == 400, "Deactivated session could still refresh"
        assert login(names["player"], password) is None, "Deactivated account could still log in"
        assert call("GET", f"/v1/users/{target}", moderator)[0] == 404, "Deactivated profile still public"
        wait_for(lambda: deactivated_in_feed(target), "Feed did not receive UserAccountChanged")
        print("PASS: deactivation disables the Keycloak account, ends its session, hides the profile and reaches Feed.", flush=True)

        status, account = call("POST", f"/v1/users/admin/accounts/{target}/reactivate", moderator)
        assert status == 200 and account["status"] == "ACTIVE"
        assert login(names["player"], password) is not None, "Reactivated account cannot log in"
        assert call("GET", f"/v1/users/{target}", moderator)[0] == 200
        wait_for(lambda: not deactivated_in_feed(target), "Feed did not receive the reactivation")
        print("PASS: reactivation restores login, profile and feed visibility.", flush=True)
    finally:
        for user_id in ids.values():
            uuid.UUID(user_id)
            admin.request("DELETE", f"{REALM}/users/{user_id}")
            subprocess.run([*COMPOSE, "exec", "-T", "user-db", "psql", "-U", "youscout_user", "-d", "youscout_user", "-v", "subject=" + user_id],
                           input="DELETE FROM account_audit WHERE subject = :'subject'; DELETE FROM user_profiles WHERE subject = :'subject';\n",
                           text=True, capture_output=True, timeout=30)
        print("Temporary accounts and profiles removed; their outbox events stay for the ordered relation stream.", flush=True)


if __name__ == "__main__":
    main()
