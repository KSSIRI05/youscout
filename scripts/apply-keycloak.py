"""Apply versioned YouScout identity settings without deleting users, databases or roles."""
import copy
import json
from urllib.parse import quote
from keycloak_admin import KeycloakAdmin, ROOT

def main():
    desired = json.loads((ROOT / "infra/keycloak/youscout-realm.json").read_text(encoding="utf-8"))
    assert desired["realm"] == "youscout", "Only the local YouScout realm is supported"
    admin = KeycloakAdmin()
    base = "realms/youscout"
    current = admin.request("GET", base)
    roles = {r["name"]: r for r in admin.request("GET", base + "/roles")}
    for name in ("user", "admin"):
        if name not in roles:
            admin.request("POST", base + "/roles", {"name": name})
        roles[name] = admin.request("GET", base + "/roles/" + name)
    settings = {key: value for key, value in desired.items() if key not in ("realm", "roles", "clients", "scopeMappings", "defaultRole", "users")}
    admin.request("PUT", base, settings)
    default_name = current["defaultRole"]["name"]
    admin.request("POST", base + "/roles/" + quote(default_name, safe="") + "/composites", [roles["user"]])
    for configured in desired["clients"]:
        client = copy.deepcopy(configured)
        existing = admin.request("GET", base + "/clients?" + "clientId=" + quote(client["clientId"]))
        assert len(existing) == 1, "Expected existing YouScout client; import the realm on first startup"
        client_id = existing[0]["id"]
        client.pop("secret", None)  # Existing client credentials are preserved.
        admin.request("PUT", base + "/clients/" + client_id, client)
        allowed = [roles["user"], roles["admin"]] if client["clientId"] == "youscout-mobile" else [roles["user"]]
        scope_path = base + "/clients/" + client_id + "/scope-mappings/realm"
        existing_scopes = admin.request("GET", scope_path)
        unwanted = [role for role in existing_scopes if role["name"] == "admin" and client["clientId"] != "youscout-mobile"]
        if unwanted:
            admin.request("DELETE", scope_path, unwanted)
        admin.request("POST", scope_path, allowed)
        if client["clientId"] == "youscout-smoke":
            account = admin.request("GET", base + "/clients/" + client_id + "/service-account-user")
            admin.request("POST", base + "/users/" + account["id"] + "/role-mappings/realm", [roles["user"]])
    print("PASS: local identity settings applied, user default role and client scopes configured. Users and existing secrets preserved.")

if __name__ == "__main__":
    main()
