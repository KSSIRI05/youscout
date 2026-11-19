"""Real Keycloak, Gateway, User, Feed, Chat and owner services check of account closure (U22).

A temporary player follows a peer and sends them a message, then closes their account through the Gateway. The
script checks the immediate effects (Keycloak disabled, refresh and login refused, actions refused, profile hidden,
Feed hides the account), then waits for every closure step: Video, Social, Rating, Chat, Skill and Notification erase the data
with User's service account, the Keycloak account is deleted and the profile is anonymised. The wait follows the
stack's ACCOUNT_CLOSURE_PURGE_DELAY (6 minutes by default; CI sets a few seconds). No password or token is printed.
Temporary accounts, profiles and messages are removed at the end.
"""
import importlib.util
import os
import re
import secrets
import subprocess
import time
import uuid
from keycloak_admin import KeycloakAdmin, ROOT

spec = importlib.util.spec_from_file_location("smoke_accounts", ROOT / "scripts" / "smoke-accounts.py")
accounts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts)
identity, call, wait_for, create_user, REALM, COMPOSE = (accounts.identity, accounts.call, accounts.wait_for,
                                                         accounts.create_user, accounts.REALM, accounts.COMPOSE)


def purge_delay_seconds():
    """Reads the delay the running User container uses (ISO-8601 PTnM/PTnS); 6 minutes when unset."""
    output = subprocess.run([*COMPOSE, "exec", "-T", "user", "printenv", "ACCOUNT_CLOSURE_PURGE_DELAY"],
                            capture_output=True, text=True, timeout=30).stdout.strip() or "PT6M"
    match = re.fullmatch(r"PT(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", output)
    assert match, "Unexpected ACCOUNT_CLOSURE_PURGE_DELAY format"
    return int(match.group(1) or 0) * 60 + float(match.group(2) or 0)


def psql(service, database, sql, subject):
    return subprocess.run([*COMPOSE, "exec", "-T", service, "psql", "-U", database, "-d", database, "-At", "-v", "subject=" + subject],
                          input=sql, text=True, encoding="utf-8", capture_output=True, timeout=30).stdout.strip()


def main():
    admin = KeycloakAdmin()
    password = "Aa1!" + secrets.token_urlsafe(24)
    names = {role: f"closure-{role}-" + secrets.token_hex(4) for role in ("player", "peer", "moderator")}
    ids = {}
    try:
        ids["player"] = create_user(admin, names["player"], password)
        ids["peer"] = create_user(admin, names["peer"], password)
        ids["moderator"] = create_user(admin, names["moderator"], password, "admin")
        player = accounts.login(names["player"], password)
        peer = accounts.login(names["peer"], password)["access_token"]
        moderator = accounts.login(names["moderator"], password)["access_token"]
        target, other = ids["player"], ids["peer"]
        for token in (player["access_token"], peer, moderator):
            assert call("GET", "/v1/users/me", token)[0] == 200
        assert call("PUT", f"/v1/users/{other}/follow", player["access_token"])[0] == 200
        assert call("POST", f"/v1/conversations/{other}/messages", player["access_token"],
                    {"clientMessageId": "closure-smoke-1", "body": "Message avant fermeture"})[0] == 201

        assert call("POST", "/v1/users/me/closure", player["access_token"], {"confirmation": "fermer"})[0] == 400
        status, closure = call("POST", "/v1/users/me/closure", player["access_token"], {"confirmation": "FERMER"})
        assert status == 202 and closure["status"] == "IN_PROGRESS", f"Closure request failed ({status})"
        assert closure["steps"][0] == {**closure["steps"][0], "name": "SESSIONS", "status": "DONE"}, "Sessions were not revoked at once"
        assert admin.request("GET", f"{REALM}/users/{target}")["enabled"] is False, "Keycloak account still enabled"
        assert identity.token_request({"grant_type": "refresh_token", "client_id": "youscout-admin",
                                       "refresh_token": player["refresh_token"]})[0] == 400, "Closed session could still refresh"
        assert accounts.login(names["player"], password) is None, "Closing account could still log in"
        assert call("GET", "/v1/users/me", player["access_token"])[0] == 403, "Closing account could still act"
        assert call("GET", f"/v1/users/{target}", peer)[0] == 404, "Closing profile still public"
        assert call("POST", f"/v1/conversations/{target}/messages", peer,
                    {"clientMessageId": "closure-smoke-2", "body": "Encore là ?"})[0] == 404, "Closing account still reachable by message"
        wait_for(lambda: accounts.deactivated_in_feed(target), "Feed did not hide the closing account")
        print("PASS: closure blocks login, refresh and actions at once, hides the profile and reaches Feed.", flush=True)

        delay = purge_delay_seconds()
        print(f"Waiting for the erasure steps (purge delay {delay:.0f} s).", flush=True)
        wait_for(lambda: call("GET", f"/v1/users/admin/accounts/{target}/closure", moderator)[1]["status"] == "COMPLETED",
                 "Closure did not complete; read GET /v1/users/admin/accounts/{subject}/closure for the failing step",
                 timeout=delay + 120)
        status, closure = call("GET", f"/v1/users/admin/accounts/{target}/closure", moderator)
        assert [step["status"] for step in closure["steps"]] == ["DONE"] * len(closure["steps"]) and len(closure["steps"]) == 8, \
            "A closure step is not done"
        try:
            admin.request("GET", f"{REALM}/users/{target}")
            raise AssertionError("Keycloak account still exists")
        except RuntimeError:
            pass
        assert psql("user-db", "youscout_user", """
            SELECT display_name || ' ' || account_status FROM user_profiles WHERE subject = :'subject';
            """, target) == "Compte fermé CLOSED", "Profile not anonymised"
        assert psql("user-db", "youscout_user", """
            SELECT count(*) FROM user_follows WHERE :'subject' IN (follower_subject, followed_subject);
            """, target) == "0", "Follows of the closed account remain"
        status, history = call("GET", f"/v1/conversations/{target}/messages", peer)
        assert status == 200 and [(m["body"], m["redacted"]) for m in history["messages"]] == [("", True)], "Message text not erased"
        print("PASS: every service confirmed the erasure, Keycloak deleted the account and the profile is anonymised.", flush=True)
    finally:
        for user_id in ids.values():
            uuid.UUID(user_id)
            try:
                admin.request("DELETE", f"{REALM}/users/{user_id}")
            except RuntimeError:
                pass  # The closed account was already deleted by User.
            psql("user-db", "youscout_user", """
                DELETE FROM account_closure_steps WHERE subject = :'subject';
                DELETE FROM account_closures WHERE subject = :'subject';
                DELETE FROM account_audit WHERE subject = :'subject';
                DELETE FROM user_follows WHERE :'subject' IN (follower_subject, followed_subject);
                DELETE FROM user_profiles WHERE subject = :'subject';
                """, user_id)
            subprocess.run([*COMPOSE, "exec", "-T", "chat-mongo", "mongosh", "--quiet", "youscout_chat", "--eval",
                            f"db.messages.deleteMany({{senderSubject: '{user_id}'}}); db.conversations.deleteMany({{'members.subject': '{user_id}'}})"],
                           capture_output=True, text=True, timeout=30)
        print("Temporary accounts, profiles and messages removed; their outbox events stay for the ordered relation stream.", flush=True)


if __name__ == "__main__":
    os.chdir(ROOT)
    main()
