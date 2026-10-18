"""Verify real Keycloak -> Gateway -> Video identity without logging credentials or writing data."""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

from keycloak_admin import local_values

API = "http://localhost:8080"
ISSUER = "http://localhost:8180/realms/youscout"


def request(url, headers=None, data=None, method=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(
                url, headers=headers or {}, data=data, method=method), timeout=15) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None, response.headers
    except urllib.error.HTTPError as error:
        # Les assertions se limitent aux codes HTTP ; ne jamais afficher le jeton ou son corps.
        return error.code, None, error.headers


def main():
    path = API + "/v1/videos/session"
    status, _, headers = request(path, {"X-User-Id": "forged", "X-User-Roles": "admin"})
    assert status == 401 and headers.get("WWW-Authenticate"), "Anonymous identity headers were accepted"
    form = urllib.parse.urlencode({
        "grant_type": "client_credentials", "client_id": "youscout-smoke",
        "client_secret": local_values()["SMOKE_CLIENT_SECRET"],
    }).encode()
    status, tokens, _ = request(ISSUER + "/protocol/openid-connect/token",
                                {"Content-Type": "application/x-www-form-urlencoded"}, form)
    assert status == 200 and tokens.get("access_token"), "Keycloak technical client authentication failed"
    token = tokens["access_token"]
    # Décodage pour comparer le sub attendu seulement ; la validation cryptographique appartient au backend.
    payload = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    bearer = {"Authorization": "Bearer " + token, "X-Request-Id": "smoke-video",
              "X-User-Id": "forged", "X-User-Roles": "admin"}
    status, identity, headers = request(path, bearer)
    assert status == 200, f"Video authenticated route failed (HTTP {status})"
    assert identity == {"subject": claims["sub"], "service": "video-service"}, "Video returned an unexpected identity"
    assert headers.get("Cache-Control") == "no-store", "Video identity must not be cached"
    assert headers.get("X-Request-Id") == "smoke-video", "Correlation ID was lost"
    assert request(API + "/v1/videos/uploads", {**bearer, "Content-Type": "application/json"}, b"{}", "POST")[0] == 400, "Invalid upload was not rejected"
    assert request(API + "/v1/videos/00000000-0000-0000-0000-000000000000/complete",
                   {**bearer, "Content-Type": "application/json"}, b"{}", "POST")[0] == 404, "Unknown completion must not reach S3"
    print("Keycloak -> Gateway -> Video identity verified; forged headers ignored; no data written.")


if __name__ == "__main__":
    main()
