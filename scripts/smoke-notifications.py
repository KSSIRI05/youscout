"""Real Keycloak, Gateway, User, Chat, Kafka and Notification check of the notification inbox.

Two temporary players: Bob follows Alice and sends her two messages. Alice receives one follow notification and
one message notification grouping both messages, registers a device (push stays DISABLED without FCM), marks
everything read, then blocks Bob, which removes their notifications. Likes and comments need a published video
(real S3), so they are covered by the integration tests. Temporary accounts and data are removed at the end.
"""
import importlib.util
import secrets
import subprocess
import uuid
from keycloak_admin import KeycloakAdmin, ROOT

spec = importlib.util.spec_from_file_location("smoke_accounts", ROOT / "scripts" / "smoke-accounts.py")
accounts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts)
call, wait_for, create_user, REALM, COMPOSE = accounts.call, accounts.wait_for, accounts.create_user, accounts.REALM, accounts.COMPOSE


def inbox(token):
    status, items = call("GET", "/v1/notifications", token)
    assert status == 200, f"Notification inbox unavailable (HTTP {status}); is the feed profile running?"
    return items


def psql(service, database, sql, subject):
    return subprocess.run([*COMPOSE, "exec", "-T", service, "psql", "-U", database, "-d", database, "-At", "-v", "subject=" + subject],
                          input=sql, text=True, encoding="utf-8", capture_output=True, timeout=30).stdout.strip()


def main():
    admin = KeycloakAdmin()
    password = "Aa1!" + secrets.token_urlsafe(24)
    names = {role: f"notify-{role}-" + secrets.token_hex(4) for role in ("alice", "bob")}
    ids = {}
    try:
        for role, name in names.items():
            ids[role] = create_user(admin, name, password)
        alice = accounts.login(names["alice"], password)["access_token"]
        bob = accounts.login(names["bob"], password)["access_token"]
        for token in (alice, bob):
            assert call("GET", "/v1/users/me", token)[0] == 200
        assert call("GET", "/v1/notifications", "invalid")[0] == 401, "Inbox reachable without a valid token"
        assert inbox(alice) == [] and call("GET", "/v1/notifications/unread-count", alice)[1] == {"count": 0}

        assert call("PUT", f"/v1/users/{ids['alice']}/follow", bob)[0] == 200
        for n in (1, 2):
            assert call("POST", f"/v1/conversations/{ids['alice']}/messages", bob,
                        {"clientMessageId": f"notify-smoke-{n}", "body": "Salut"})[0] == 201
        wait_for(lambda: sorted((i["type"], i["occurrences"]) for i in inbox(alice)) == [("FOLLOWED", 1), ("MESSAGE", 2)],
                 "Alice did not receive the follow and grouped message notifications (User and Chat outboxes, Kafka)")
        assert all(i["actorSubject"] == ids["bob"] and not i["read"] for i in inbox(alice))
        assert call("GET", "/v1/notifications/unread-count", alice)[1] == {"count": 2}
        assert inbox(bob) == [], "Bob was notified of his own actions"
        print("PASS: follow and grouped messages reach the recipient's inbox through Kafka.", flush=True)

        assert call("PUT", "/v1/notifications/devices", alice, {"platform": "WEB", "token": "smoke-" + secrets.token_hex(16)})[0] == 204
        wait_for(lambda: psql("notification-db", "youscout_notification",
                              "SELECT string_agg(DISTINCT push_status, ',') FROM notifications WHERE recipient_subject = :'subject';",
                              ids["alice"]) == "DISABLED", "Push status not recorded (expected DISABLED without FCM)")
        first = inbox(alice)[0]["id"]
        assert call("POST", f"/v1/notifications/{first}/read", bob)[0] == 404, "Bob could mark Alice's notification"
        assert call("POST", "/v1/notifications/read-all", alice)[0] == 204
        assert call("GET", "/v1/notifications/unread-count", alice)[1] == {"count": 0}
        assert call("PUT", f"/v1/users/{ids['bob']}/block", alice)[0] == 200
        wait_for(lambda: inbox(alice) == [], "Blocking did not remove the notifications between the two accounts")
        print("PASS: device registered (push DISABLED without FCM), read state is private, a block clears the pair.", flush=True)
    finally:
        for user_id in ids.values():
            uuid.UUID(user_id)
            try:
                admin.request("DELETE", f"{REALM}/users/{user_id}")
            except RuntimeError:
                pass
            psql("user-db", "youscout_user", """
                DELETE FROM user_follows WHERE :'subject' IN (follower_subject, followed_subject);
                DELETE FROM user_blocks WHERE :'subject' IN (blocker_subject, blocked_subject);
                DELETE FROM user_profiles WHERE subject = :'subject';
                """, user_id)
            psql("notification-db", "youscout_notification", """
                DELETE FROM notifications WHERE :'subject' IN (recipient_subject, actor_subject);
                DELETE FROM devices WHERE subject = :'subject';
                DELETE FROM relation_follows WHERE :'subject' IN (follower_subject, followed_subject);
                DELETE FROM relation_blocks WHERE :'subject' IN (blocker_subject, blocked_subject);
                """, user_id)
            subprocess.run([*COMPOSE, "exec", "-T", "chat-mongo", "mongosh", "--quiet", "youscout_chat", "--eval",
                            f"db.messages.deleteMany({{senderSubject: '{user_id}'}}); db.conversations.deleteMany({{'members.subject': '{user_id}'}});"
                            f" db.chat_outbox.deleteMany({{senderSubject: '{user_id}'}})"],
                           capture_output=True, text=True, timeout=30)
        print("Temporary accounts, notifications and messages removed; published Kafka events stay until retention.", flush=True)


if __name__ == "__main__":
    main()
