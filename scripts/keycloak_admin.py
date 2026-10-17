"""Local-only Keycloak provisioning helpers. Never print credentials or bearer tokens."""
import json
import time
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ORIGIN = "http://localhost:8180"
ROOT = Path(__file__).resolve().parents[1]

def local_values():
    return dict(line.split("=", 1) for line in (ROOT / "infra/compose/.env").read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#"))

class KeycloakAdmin:
    def __init__(self):
        form = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": "admin",
                                      "password": local_values()["KEYCLOAK_ADMIN_PASSWORD"]}).encode()
        # Operator bootstrap only: mobile and service clients keep direct grants disabled.
        request = urllib.request.Request(ORIGIN + "/realms/master/protocol/openid-connect/token", data=form)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                tokens = json.load(response)
                self._token = tokens["access_token"]
                self._refresh = tokens["refresh_token"]
                self._expires = time.monotonic() + tokens["expires_in"] - 10
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Local Keycloak administrator authentication failed (HTTP {error.code}).") from None

    def request(self, method, path, value=None):
        if time.monotonic() >= self._expires:
            form = urllib.parse.urlencode({"grant_type": "refresh_token", "client_id": "admin-cli", "refresh_token": self._refresh}).encode()
            request = urllib.request.Request(ORIGIN + "/realms/master/protocol/openid-connect/token", data=form)
            try:
                with urllib.request.urlopen(request, timeout=15) as response:
                    tokens = json.load(response)
                    self._token, self._refresh = tokens["access_token"], tokens["refresh_token"]
                    self._expires = time.monotonic() + tokens["expires_in"] - 10
            except urllib.error.HTTPError as error:
                raise RuntimeError(f"Local Keycloak administrator renewal failed (HTTP {error.code}).") from None
        data = json.dumps(value).encode() if value is not None else None
        request = urllib.request.Request(ORIGIN + "/admin/" + path, data=data, method=method,
            headers={"Authorization": "Bearer " + self._token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Local Keycloak administration failed (HTTP {error.code}, {method}).") from None
