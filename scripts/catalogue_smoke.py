"""Exercise catalogue rights through the Gateway; only generated fixtures are cleaned up."""
import json
import secrets
import subprocess
import urllib.error
import urllib.request
import uuid
from keycloak_admin import ROOT

API = "http://localhost:8080"

# Utilise uniquement la Gateway locale pour exercer aussi ses règles de sécurité.
def request(method, path, token, value=None):
    headers = {"Authorization": "Bearer " + token}
    data = None
    if value is not None:
        data = json.dumps(value).encode()
        headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, data=data, headers=headers, method=method), timeout=15) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        body = error.read()
        return error.code, json.loads(body) if body else None

def exercise(player_token, admin_token):
    skill_id = tag_id = None
    # Les noms aléatoires isolent ces données des compétences et hashtags du projet.
    suffix = secrets.token_hex(8)
    try:
        status, page = request("GET", "/v1/skills", player_token)
        assert status == 200 and isinstance(page["items"], list), "Catalogue read failed"
        assert request("POST", "/v1/skills/admin", player_token, {"name": "Denied " + suffix})[0] == 403, "Player could create a skill"
        status, skill = request("POST", "/v1/skills/admin", admin_token, {"name": "Smoke " + suffix, "description": "Temporary test skill"})
        assert status == 201, "Admin skill creation failed"
        skill_id = str(uuid.UUID(skill["id"]))
        path = "/v1/skills/admin/" + skill_id
        status, _ = request("PATCH", path, admin_token, {"name": "Renamed " + suffix, "active": False})
        assert status == 200 and request("GET", "/v1/skills/" + skill_id, player_token)[0] == 404, "Inactive skill remained selectable"
        status, skill = request("GET", path, admin_token)
        assert status == 200 and not skill["active"] and skill["description"] == "Temporary test skill", "Admin read/partial patch failed"
        assert request("PATCH", path, admin_token, {"active": True})[0] == 200, "Skill reactivation failed"
        assert request("GET", "/v1/skills/" + skill_id, player_token)[0] == 200, "Reactivated skill inaccessible"
        status, tag = request("POST", "/v1/hashtags", player_token, {"name": "#Catalog_Smoke_" + suffix})
        assert status == 201, "Hashtag creation failed"
        tag_id = str(uuid.UUID(tag["id"]))
        status, repeated = request("POST", "/v1/hashtags", player_token, {"name": tag["name"].upper()})
        assert status == 200 and repeated["id"] == tag_id, "Canonical hashtag duplicated"
        # Les requêtes répétées doivent conserver une seule ligne puis supprimer cet abonnement.
        follow = "/v1/hashtags/" + tag_id + "/follow"
        assert request("PUT", follow, player_token)[0] == 204 and request("PUT", follow, player_token)[0] == 204, "Idempotent follow failed"
        status, page = request("GET", "/v1/hashtags/following?limit=100", player_token)
        assert status == 200 and any(item["id"] == tag_id for item in page["items"]), "Own followed hashtag missing"
        assert request("DELETE", follow, player_token)[0] == 204 and request("DELETE", follow, player_token)[0] == 204, "Idempotent unfollow failed"
        status, page = request("GET", "/v1/hashtags/following?limit=100", player_token)
        assert status == 200 and all(item["id"] != tag_id for item in page["items"]), "Unfollow did not persist"
        print("PASS: catalogue reads, admin skill lifecycle, hashtag normalization and own follow/unfollow.", flush=True)
    # Nettoie uniquement les UUID créés pendant ce parcours, même si une assertion échoue.
    finally:
        statements = []
        arguments = []
        if tag_id:
            arguments += ["-v", "tag_id=" + tag_id]
            statements += ["DELETE FROM hashtag_follows WHERE hashtag_id=:'tag_id'::uuid;", "DELETE FROM hashtags WHERE id=:'tag_id'::uuid;"]
        if skill_id:
            arguments += ["-v", "skill_id=" + skill_id]
            statements += ["DELETE FROM skill_audit WHERE skill_id=:'skill_id'::uuid;", "DELETE FROM skills WHERE id=:'skill_id'::uuid;"]
        if statements:
            # Passe les UUID à psql comme variables ; aucun identifiant utilisateur n’est concaténé au SQL.
            subprocess.run(["docker", "compose", "-f", str(ROOT / "infra/compose/compose.yml"), "exec", "-T", "skill-db",
                "psql", "-U", "youscout_skill", "-d", "youscout_skill", "-v", "ON_ERROR_STOP=1", *arguments],
                input="BEGIN;\n" + "\n".join(statements) + "\nCOMMIT;\n", text=True, check=True, stdout=subprocess.DEVNULL)
