"""Exercise real Keycloak -> Gateway -> User without printing tokens or secrets."""
import argparse
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "http://localhost:8080"
ISSUER = "http://localhost:8180/realms/youscout"

def request(url, data=None, headers=None, method=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}, method=method), timeout=15) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None, response.headers
    except urllib.error.HTTPError as error:
        body = error.read()
        return error.code, json.loads(body) if body else None, error.headers

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-persisted-profile", action="store_true", help="Require the previous smoke profile before writing; use after restarting User.")
    options = parser.parse_args()
    env_path = Path(__file__).resolve().parents[1] / "infra/compose/.env"
    values = dict(line.split("=", 1) for line in env_path.read_text().splitlines() if line and not line.startswith("#"))
    status, health, _ = request(BASE + "/actuator/health")
    assert status == 200 and health["status"] == "UP", "Gateway health failed"
    status, config, _ = request(ISSUER + "/.well-known/openid-configuration")
    assert status == 200 and config["issuer"] == ISSUER, "Issuer mismatch"
    status, _, headers = request(BASE + "/v1/users/session")
    assert status == 401 and headers.get("X-Request-Id"), "Missing bearer token was not rejected"
    data = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "youscout-smoke", "client_secret": values["SMOKE_CLIENT_SECRET"]}).encode()
    status, tokens, _ = request(ISSUER + "/protocol/openid-connect/token", data, {"Content-Type": "application/x-www-form-urlencoded"})
    assert status == 200 and "access_token" in tokens, "Service-account authentication failed"
    bearer = {"Authorization": "Bearer " + tokens["access_token"], "X-Request-Id": "smoke-backend"}
    status, identity, headers = request(BASE + "/v1/users/session", headers=bearer)
    assert status == 200 and identity["service"] == "user-service" and identity["subject"], "Authenticated routing failed"
    assert headers.get("X-Request-Id") == "smoke-backend", "Correlation header missing"
    status, denied, _ = request(BASE + "/v1/users/admin/session", headers=bearer)
    assert status == 403 and denied["status"] == 403, "Smoke user must not access admin endpoints"
    status, profile, profile_headers = request(BASE + "/v1/users/me", headers=bearer)
    assert status == 200 and profile["subject"] == identity["subject"], "Own profile provisioning failed"
    assert profile_headers.get("Cache-Control") == "no-store", "Profile must not be cached"
    expected = {"displayName": "YouScout Smoke", "biography": "Profil technique de test."}
    if options.verify_persisted_profile:
        assert all(profile[key] == value for key, value in expected.items()), "Previous profile did not survive restart"
    payload = json.dumps(expected).encode()
    status, profile, _ = request(BASE + "/v1/users/me", payload, {**bearer, "Content-Type": "application/json"}, "PATCH")
    assert status == 200 and profile["subject"] == identity["subject"], "Own profile patch failed"
    status, saved, _ = request(BASE + "/v1/users/me", headers=bearer)
    assert status == 200 and all(saved[key] == value for key, value in expected.items()), "Profile changes were not persisted"
    status, skills, _ = request(BASE + "/v1/skills", headers=bearer)
    assert status == 200 and isinstance(skills["items"], list), "Skill catalogue unavailable"
    status, followed, _ = request(BASE + "/v1/hashtags/following?limit=100", headers=bearer)
    assert status == 200, "Followed hashtags unavailable"
    if options.verify_persisted_profile:
        assert any(tag["name"] == "youscout_smoke" for tag in followed["items"]), "Followed hashtag did not survive restart"
    status, tag, _ = request(BASE + "/v1/hashtags", json.dumps({"name": "#YouScout_Smoke"}).encode(), {**bearer, "Content-Type": "application/json"}, "POST")
    assert status in (200, 201), "Technical hashtag provisioning failed"
    status, _, _ = request(BASE + "/v1/hashtags/" + tag["id"] + "/follow", headers=bearer, method="PUT")
    assert status == 204, "Technical hashtag follow failed"
    status, _, _ = request(BASE + "/v1/users/me", headers={"Authorization": "Bearer malformed"})
    assert status == 401, "Invalid bearer token was not rejected"
    print("PASS: health, OIDC discovery, anonymous rejection, real signed token, secured routing, correlation, persistent profile reads/patches, admin denial and invalid-token rejection.")
    print("The service account proves infrastructure authentication, not an interactive mobile user login. Only its own technical test profile is modified.")

if __name__ == "__main__":
    main()
