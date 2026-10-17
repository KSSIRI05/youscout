"""Real local Keycloak registration/login/PKCE/RBAC tests; temporary user is removed."""
import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
import secrets
import subprocess
import uuid
import urllib.error
import urllib.parse
import urllib.request
from keycloak_admin import KeycloakAdmin, ROOT
from catalogue_smoke import exercise as exercise_catalogue

ISSUER = "http://localhost:8180/realms/youscout"
CALLBACK = "http://localhost:7357/auth/callback"
API = "http://localhost:8080"

class Page(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.forms, self.links, self.current = [], [], None
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self.current = {"id": attrs.get("id"), "action": attrs.get("action"), "hidden": {}}
            self.forms.append(self.current)
        if tag == "input" and self.current is not None and attrs.get("type") == "hidden" and attrs.get("name"):
            self.current["hidden"][attrs["name"]] = attrs.get("value", "")
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
    def handle_endtag(self, tag):
        if tag == "form": self.current = None
    def form(self, name):
        found = [form for form in self.forms if form["id"] == name]
        assert len(found) == 1, "Expected Keycloak form was not rendered"
        return found[0]

class LocalRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if newurl.startswith(CALLBACK + "?"):
            return None  # Capture the code without a callback server, never print it.
        assert urllib.parse.urlsplit(newurl).netloc == "localhost:8180", "Unexpected non-local redirect"
        return super().redirect_request(request, fp, code, message, headers, newurl)

class LocalhostCookies(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Browsers allow Secure cookies on localhost. Emulate only our fixed local origin.
        return request.host == "localhost:8180" or super().return_ok_secure(cookie, request)

class BrowserSession:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(policy=LocalhostCookies())), LocalRedirects())
    def fetch(self, url, values=None):
        url = urllib.parse.urljoin(getattr(self, "last_url", ISSUER + "/"), url)
        self.last_url = url
        data = urllib.parse.urlencode(values).encode() if values is not None else None
        try:
            with self.opener.open(urllib.request.Request(url, data=data), timeout=15) as response:
                return response.status, response.read().decode(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), error.headers

def challenge():
    verifier = secrets.token_urlsafe(48)
    proof = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, proof

def authorization(browser, registration=False, credentials=None, override=None):
    verifier, proof = challenge()
    state = secrets.token_urlsafe(24)
    values = {"client_id": "youscout-mobile", "response_type": "code", "scope": "openid profile email",
              "redirect_uri": CALLBACK, "code_challenge": proof, "code_challenge_method": "S256", "state": state, "nonce": secrets.token_urlsafe(24)}
    if override:
        for key, value in override.items():
            if value is None: values.pop(key, None)
            else: values[key] = value
    status, html, headers = browser.fetch(ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(values))
    if override:
        if status in (302, 303) and headers.get("Location", "").startswith(CALLBACK + "?"):
            params = urllib.parse.parse_qs(urllib.parse.urlsplit(headers["Location"]).query)
            assert "error" in params and "code" not in params, "Unsafe OIDC request returned a code"
        else:
            assert status == 400, "Unsafe OIDC request was not rejected"
        return None
    assert status == 200, "Login page was not available"
    page = Page(html)
    if registration:
        links = [url for url in page.links if "/login-actions/registration?" in url]
        assert len(links) == 1, "Self-registration link missing"
        status, html, _ = browser.fetch(links[0])
        assert status == 200, "Registration page unavailable"
        form = Page(html).form("kc-register-form")
    else:
        form = page.form("kc-form-login")
    status, html, headers = browser.fetch(form["action"], {**form["hidden"], **credentials})
    location = headers.get("Location", "")
    assert status in (302, 303) and location.startswith(CALLBACK + "?"), "Registration/login did not return an authorization code"
    params = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    assert params.get("state") == [state] and "code" in params, "OIDC callback state/code invalid"
    return params["code"][0], verifier

def token_request(values, endpoint="token"):
    data = urllib.parse.urlencode(values).encode()
    request = urllib.request.Request(ISSUER + "/protocol/openid-connect/" + endpoint, data=data)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

def exchange(code, verifier):
    return token_request({"grant_type": "authorization_code", "client_id": "youscout-mobile", "redirect_uri": CALLBACK,
                          "code": code, "code_verifier": verifier})

def api(path, token):
    request = urllib.request.Request(API + path, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

def claims(token):
    # Inspection only. API authorization still verifies the signature and claims.
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))

def main():
    admin = KeycloakAdmin()
    clients = admin.request("GET", "realms/youscout/clients?clientId=youscout-mobile")
    assert len(clients) == 1, "Mobile client missing"
    defaults = admin.request("GET", "realms/youscout/clients/" + clients[0]["id"] + "/default-client-scopes")
    assert "basic" in {scope["name"] for scope in defaults}, "Mobile client requires default scope basic to emit sub"
    username = "identity-test-" + secrets.token_hex(8)
    password = "Aa1!" + secrets.token_urlsafe(32)
    user_id = None
    try:
        print("Checking PKCE and redirect restrictions...", flush=True)
        for override in ({"code_challenge": None, "code_challenge_method": None},
                         {"code_challenge_method": "plain"}, {"redirect_uri": "http://untrusted.invalid/callback"}):
            authorization(BrowserSession(), override=override)
        print("Registering a temporary player through the public Keycloak form...", flush=True)
        code, verifier = authorization(BrowserSession(), registration=True, credentials={"username": username,
            "email": username + "@example.invalid", "firstName": "Test", "lastName": "YouScout", "password": password, "password-confirm": password})
        users = admin.request("GET", "realms/youscout/users?username=" + username + "&exact=true")
        assert len(users) == 1 and users[0]["username"] == username, "Temporary registered user not found"
        user_id = users[0]["id"]
        status, tokens = exchange(code, verifier)
        assert status == 200, "PKCE code exchange failed"
        identity = claims(tokens["access_token"])
        assert identity.get("sub") == user_id, "Access token subject missing or incorrect; verify default scope basic"
        roles = identity.get("realm_access", {}).get("roles", [])
        assert "user" in roles and "admin" not in roles, "Registration role escalation/default-role failure"
        status, profile = api("/v1/users/me", tokens["access_token"])
        assert status == 200, f"Registered player's profile access failed (HTTP {status})"
        assert profile.get("subject") == user_id, "Registered player's profile owner mismatch"
        status, problem = api("/v1/users/admin/session", tokens["access_token"])
        assert status == 403 and problem["status"] == 403, "Player reached admin endpoint"
        assert exchange(code, verifier)[0] == 400, "Authorization code was reusable"
        login = {"username": username, "password": password}
        print("Checking browser login, bad credentials and PKCE verifier...", flush=True)
        browser = BrowserSession()
        _, html, _ = browser.fetch(ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode({
            "client_id": "youscout-mobile", "response_type": "code", "scope": "openid", "redirect_uri": CALLBACK,
            "code_challenge": challenge()[1], "code_challenge_method": "S256", "state": secrets.token_urlsafe(24)}))
        form = Page(html).form("kc-form-login")
        status, _, headers = browser.fetch(form["action"], {**form["hidden"], "username": username, "password": "Wrong-Aa1!-password"})
        assert status == 200 and not headers.get("Location", "").startswith(CALLBACK), "Bad password accepted"
        code, _ = authorization(BrowserSession(), credentials=login)
        assert exchange(code, challenge()[0])[0] == 400, "Incorrect PKCE verifier accepted"
        assert token_request({"grant_type": "password", "client_id": "youscout-mobile", **login})[0] == 400, "Mobile password grant unexpectedly enabled"
        code, verifier = authorization(BrowserSession(), credentials=login)
        status, tokens = exchange(code, verifier)
        assert status == 200, "Returning player login failed"
        old_refresh = tokens["refresh_token"]
        status, refreshed = token_request({"grant_type": "refresh_token", "client_id": "youscout-mobile", "refresh_token": old_refresh})
        assert status == 200 and refreshed["refresh_token"] != old_refresh, "Refresh rotation failed"
        assert token_request({"grant_type": "refresh_token", "client_id": "youscout-mobile", "refresh_token": old_refresh})[0] == 400, "Used refresh token was accepted"
        player_access = tokens["access_token"]
        print("Checking controlled admin assignment and logout...", flush=True)
        role = admin.request("GET", "realms/youscout/roles/admin")
        admin.request("POST", "realms/youscout/users/" + user_id + "/role-mappings/realm", [role])
        code, verifier = authorization(BrowserSession(), credentials=login)
        status, tokens = exchange(code, verifier)
        assert status == 200 and "admin" in claims(tokens["access_token"])["realm_access"]["roles"], "Admin role not mapped to token"
        assert api("/v1/users/admin/session", tokens["access_token"])[0] == 200, "Authorized admin denied"
        exercise_catalogue(player_access, tokens["access_token"])
        assert token_request({"client_id": "youscout-mobile", "refresh_token": tokens["refresh_token"]}, "logout")[0] == 204, "Logout failed"
        assert token_request({"grant_type": "refresh_token", "client_id": "youscout-mobile", "refresh_token": tokens["refresh_token"]})[0] == 400, "Logged-out session could refresh"
        print("PASS: public registration, user default role, login, PKCE, redirect restrictions, refresh rotation, user/admin RBAC and logout.", flush=True)
    finally:
        if user_id is None:
            # Registration may have succeeded before a later assertion failed.
            users = admin.request("GET", "realms/youscout/users?username=" + username + "&exact=true")
            if len(users) == 1 and users[0]["username"] == username: user_id = users[0]["id"]
        if user_id is not None:
            uuid.UUID(user_id)  # Restrict cleanup to the UUID of this generated test account.
            admin.request("DELETE", "realms/youscout/users/" + user_id)
            subprocess.run(["docker", "compose", "-f", str(ROOT / "infra/compose/compose.yml"), "exec", "-T", "user-db",
                "psql", "-U", "youscout_user", "-d", "youscout_user", "-v", "ON_ERROR_STOP=1", "-v", "profile_subject=" + user_id],
                input="DELETE FROM user_profiles WHERE subject = :'profile_subject';\n", text=True, check=True, stdout=subprocess.DEVNULL)
            print("Temporary identity and its test profile removed.", flush=True)

if __name__ == "__main__":
    main()
