"""Real Keycloak, Gateway, User, Video, Social, Rating, Kafka and Analytics check of the admin lists and dashboard.

A temporary admin and player are created. The player's first profile read publishes UserProfileCreated; today's
new-account count in Analytics must rise. The admin lists and finds the player (A01), reads the video and comment
lists with author names (A02) and every dashboard total (A06); the player is refused everywhere. No token or
password is printed; temporary accounts and profiles are removed at the end.
"""
import datetime
import importlib.util
import secrets
import subprocess
import urllib.parse
import uuid
from keycloak_admin import KeycloakAdmin, ROOT

spec = importlib.util.spec_from_file_location("smoke_accounts", ROOT / "scripts" / "smoke-accounts.py")
accounts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts)
call, wait_for, create_user, REALM, COMPOSE = accounts.call, accounts.wait_for, accounts.create_user, accounts.REALM, accounts.COMPOSE


def today_new_accounts(admin_token):
    status, activity = call("GET", "/v1/analytics/activity?days=1", admin_token)
    assert status == 200, f"Analytics unavailable (HTTP {status}); is the feed profile running?"
    assert activity["days"][-1]["day"] == datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    return activity["days"][-1]["newAccounts"]


def main():
    admin = KeycloakAdmin()
    password = "Aa1!" + secrets.token_urlsafe(24)
    names = {"player": "admin-smoke-player-" + secrets.token_hex(4), "moderator": "admin-smoke-admin-" + secrets.token_hex(4)}
    ids = {}
    try:
        ids["moderator"] = create_user(admin, names["moderator"], password, "admin")
        moderator = accounts.login(names["moderator"], password)["access_token"]
        assert call("GET", "/v1/users/me", moderator)[0] == 200
        before = today_new_accounts(moderator)

        ids["player"] = create_user(admin, names["player"], password)
        player = accounts.login(names["player"], password)["access_token"]
        status, profile = call("GET", "/v1/users/me", player)
        assert status == 200
        # Le compte admin peut encore arriver : on vérifie la hausse ; le décompte unique est couvert par les tests d'intégration.
        wait_for(lambda: today_new_accounts(moderator) >= before + 1, "Analytics did not count the new profile (UserProfileCreated)")
        print("PASS: a new profile reaches Analytics through the User outbox and Kafka.", flush=True)

        # Tous les comptes de smoke s'appellent « Account Check » : un nom unique isole le joueur dans la recherche A01.
        status, profile = call("PATCH", "/v1/users/me", player, {"displayName": "Admin Smoke " + secrets.token_hex(4)})
        assert status == 200, f"Profile rename failed (HTTP {status})"
        query = urllib.parse.quote(profile["displayName"])
        status, found = call("GET", f"/v1/users/admin/accounts?q={query}&limit=5", moderator)
        assert status == 200 and [a["subject"] for a in found] == [ids["player"]], "A01 search did not find the player"
        status, latest = call("GET", "/v1/users/admin/accounts?limit=2", moderator)
        assert status == 200 and latest[0]["subject"] == ids["player"], "A01 list is not newest first"
        status, resolved = call("GET", f"/v1/users/admin/accounts/names?subject={ids['player']}&subject={uuid.uuid4()}", moderator)
        assert status == 200 and resolved == {ids["player"]: profile["displayName"]}
        for path in ("/v1/videos/admin?limit=5", "/v1/social/admin/comments?limit=5"):
            status, rows = call("GET", path, moderator)
            assert status == 200 and isinstance(rows, list), f"A02 list {path} failed (HTTP {status})"
        totals = {path: call("GET", path, moderator) for path in ("/v1/users/admin/accounts/stats", "/v1/videos/admin/stats",
                                                                  "/v1/social/admin/stats", "/v1/ratings/admin/stats")}
        assert all(status == 200 for status, _ in totals.values()), {path: status for path, (status, _) in totals.items()}
        assert totals["/v1/users/admin/accounts/stats"][1]["accounts"]["ACTIVE"] >= 2
        assert set(totals["/v1/videos/admin/stats"][1]) == {"videos", "published", "removed"}
        assert set(totals["/v1/social/admin/stats"][1]) == {"comments", "likes", "openReports"}
        assert set(totals["/v1/ratings/admin/stats"][1]) == {"ratings", "raters", "ratedVideos"}
        for path in ("/v1/users/admin/accounts", "/v1/videos/admin", "/v1/social/admin/comments", "/v1/users/admin/accounts/stats",
                     "/v1/videos/admin/stats", "/v1/social/admin/stats", "/v1/ratings/admin/stats", "/v1/analytics/activity"):
            assert call("GET", path, player)[0] == 403, f"Player reached {path}"
        print("PASS: admin lists (A01/A02) and dashboard totals (A06) answer through the Gateway; players are refused.", flush=True)
    finally:
        for user_id in ids.values():
            uuid.UUID(user_id)
            try:
                admin.request("DELETE", f"{REALM}/users/{user_id}")
            except RuntimeError:
                pass
            subprocess.run([*COMPOSE, "exec", "-T", "user-db", "psql", "-U", "youscout_user", "-d", "youscout_user", "-v", "subject=" + user_id],
                           input="DELETE FROM user_profiles WHERE subject = :'subject';\n", text=True, capture_output=True, timeout=30)
        print("Temporary accounts and profiles removed; the daily counters keep their aggregate counts.", flush=True)


if __name__ == "__main__":
    main()
