"""Apply versioned YouScout identity settings without deleting users, databases or roles."""
import copy
import json
from urllib.parse import quote
from keycloak_admin import KeycloakAdmin, ROOT, local_values

def main():
    # Le JSON versionné décrit la configuration souhaitée ; les utilisateurs existants sont conservés.
    desired = json.loads((ROOT / "infra/keycloak/youscout-realm.json").read_text(encoding="utf-8"))
    assert desired["realm"] == "youscout", "Only the local YouScout realm is supported"
    admin = KeycloakAdmin()
    base = "realms/youscout"
    current = admin.request("GET", base)
    roles = {r["name"]: r for r in admin.request("GET", base + "/roles")}
    for name in ("user", "admin", "account-closure"):
        if name not in roles:
            admin.request("POST", base + "/roles", {"name": name})
        roles[name] = admin.request("GET", base + "/roles/" + name)
    # N’envoie pas les collections gérées séparément : rôles, clients et comptes.
    settings = {key: value for key, value in desired.items() if key not in ("realm", "roles", "clients", "scopeMappings", "defaultRole", "users")}
    admin.request("PUT", base, settings)
    # Tout nouveau compte reçoit user ; l’attribution admin reste une opération explicite.
    default_name = current["defaultRole"]["name"]
    admin.request("POST", base + "/roles/" + quote(default_name, safe="") + "/composites", [roles["user"]])
    client_scopes = {scope["name"]: scope["id"] for scope in admin.request("GET", base + "/client-scopes")}
    for configured in desired["clients"]:
        client = copy.deepcopy(configured)
        existing = admin.request("GET", base + "/clients?" + "clientId=" + quote(client["clientId"]))
        if not existing and client.get("publicClient"):
            # Un client public ajouté après l'import initial (ex. youscout-admin) est créé ; il n'a pas de secret.
            admin.request("POST", base + "/clients", client)
            existing = admin.request("GET", base + "/clients?" + "clientId=" + quote(client["clientId"]))
        elif not existing and client["clientId"] == "youscout-user-admin":
            # Client confidentiel ajouté après l'import : son secret vient du .env local, jamais du dépôt.
            admin.request("POST", base + "/clients", {**client, "secret": local_values()["USER_ADMIN_CLIENT_SECRET"]})
            existing = admin.request("GET", base + "/clients?" + "clientId=" + quote(client["clientId"]))
        assert len(existing) == 1, "Expected existing YouScout client; import the realm on first startup"
        client_id = existing[0]["id"]
        # Le client existe déjà : son secret courant ne doit pas être remplacé par celui de l’import.
        client.pop("secret", None)  # Existing client credentials are preserved.
        admin.request("PUT", base + "/clients/" + client_id, client)
        # La mise à jour du client ne crée pas les mappers ajoutés après l'import : ils sont ajoutés par nom.
        mappers_path = base + "/clients/" + client_id + "/protocol-mappers/models"
        present = {mapper["name"] for mapper in admin.request("GET", mappers_path)}
        for mapper in client.get("protocolMappers", []):
            if mapper["name"] not in present:
                admin.request("POST", mappers_path, mapper)
        # Updating ClientRepresentation does not attach client-scope relationships.
        # Explicitly link defaults to repair existing realms as well as new imports.
        # Le scope basic fournit notamment sub, nécessaire aux profils et abonnements.
        for name in client.get("defaultClientScopes", []):
            assert name in client_scopes, "Required built-in client scope missing: " + name
            admin.request("PUT", base + "/clients/" + client_id + "/default-client-scopes/" + client_scopes[name])
        # Le mobile et l'admin peuvent porter les rôles applicatifs ; le client de test technique reste limité à user.
        carries_admin = client["clientId"] in ("youscout-mobile", "youscout-admin")
        allowed = [roles["user"], roles["admin"]] if carries_admin else [roles["user"]]
        scope_path = base + "/clients/" + client_id + "/scope-mappings/realm"
        existing_scopes = admin.request("GET", scope_path)
        unwanted = [role for role in existing_scopes if role["name"] == "admin" and not carries_admin]
        if unwanted:
            admin.request("DELETE", scope_path, unwanted)
        admin.request("POST", scope_path, allowed)
        # Attribue le rôle au compte de service lui-même, en plus de l’autoriser dans le scope client.
        if client["clientId"] == "youscout-user-admin":
            # Seuls les droits de gestion des utilisateurs du realm sont accordés au compte de service.
            account = admin.request("GET", base + "/clients/" + client_id + "/service-account-user")
            management = admin.request("GET", base + "/clients?clientId=realm-management")[0]["id"]
            roles_needed = [admin.request("GET", base + "/clients/" + management + "/roles/" + name) for name in ("manage-users", "view-users")]
            admin.request("POST", base + "/users/" + account["id"] + "/role-mappings/clients/" + management, roles_needed)
            # U22 : son jeton (audience youscout-api) autorise les effacements internes des services, et rien d'autre :
            # le rôle par défaut, qui donnerait user, lui est retiré.
            realm_path = base + "/users/" + account["id"] + "/role-mappings/realm"
            defaults = [role for role in admin.request("GET", realm_path) if role["name"] in (default_name, "user")]
            if defaults:
                admin.request("DELETE", realm_path, defaults)
            admin.request("POST", realm_path, [roles["account-closure"]])
        if client["clientId"] == "youscout-smoke":
            account = admin.request("GET", base + "/clients/" + client_id + "/service-account-user")
            admin.request("POST", base + "/users/" + account["id"] + "/role-mappings/realm", [roles["user"]])
    print("PASS: local identity settings applied, user default role and client scopes configured. Users and existing secrets preserved.")

if __name__ == "__main__":
    main()
