"""Check upload tickets and persistence; optional tiny S3 PUT. Remove only generated fixtures."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from keycloak_admin import local_values, ROOT

API = "http://localhost:8080"
BUCKET = "youscout-014663857971-dev-videos"


def request(url, data=None, headers=None, method=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}, method=method), timeout=15) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        return error.code, None


def compose(*args):
    result = subprocess.run(["docker", "compose", "-f", "infra/compose/compose.yml", "--profile", "video", *args],
                            cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("Upload smoke Docker operation failed; inspect local container health.")
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--put-object", action="store_true", help="Write a tiny test object to real S3, then delete it using the provisioning role.")
    parser.add_argument("--verify-restart", action="store_true", help="Restart Video and require the same persisted upload ID on retry.")
    parser.add_argument("--complete", action="store_true", help="Confirm the real S3 upload and verify idempotent completion (requires --put-object).")
    parser.add_argument("--verify-outbox", action="store_true", help="Wait for the running relay to publish the confirmation event (requires --complete).")
    parser.add_argument("--aws", default=shutil.which("aws") or str(Path.home() / "AppData/Local/Programs/Amazon/AWSCLIV2/aws.exe"))
    options = parser.parse_args()
    if options.complete and not options.put_object:
        parser.error("--complete requires --put-object")
    if options.verify_outbox and not options.complete:
        parser.error("--verify-outbox requires --complete")
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "youscout-smoke",
                                  "client_secret": local_values()["SMOKE_CLIENT_SECRET"]}).encode()
    status, tokens = request("http://localhost:8180/realms/youscout/protocol/openid-connect/token", form,
                             {"Content-Type": "application/x-www-form-urlencoded"})
    assert status == 200, "Technical identity authentication failed"
    key = str(uuid.uuid4())
    headers = {"Authorization": "Bearer " + tokens["access_token"], "Idempotency-Key": key, "Content-Type": "application/json"}
    # Ce contenu teste le transport S3 uniquement, pas la validité d'un média vidéo.
    content = b"youscout-smoke"
    data = json.dumps({"title": "Temporary upload smoke", "contentType": "video/mp4", "sizeBytes": len(content)}).encode()
    object_key = None
    attempted_put = False
    try:
        status, ticket = request(API + "/v1/videos/uploads", data, headers, "POST")
        assert status == 201, f"Upload ticket failed (HTTP {status}); check Video role credentials"
        upload_id = str(uuid.UUID(ticket["id"]))
        object_key = "videos/" + upload_id + "/source"
        assert ticket["status"] == "PENDING_UPLOAD", "Unexpected upload state"
        if options.put_object:
            attempted_put = True
            try:
                with urllib.request.urlopen(urllib.request.Request(ticket["upload"]["url"], data=content,
                        headers=ticket["upload"]["headers"], method="PUT"), timeout=30) as response:
                    assert response.status == 200, "S3 PUT failed"
            except (urllib.error.URLError, AssertionError):
                raise RuntimeError("Real S3 PUT failed; signed URL omitted from diagnostics.") from None
        confirmation = None
        if options.complete:
            status, confirmation = request(API + "/v1/videos/" + upload_id + "/complete", b"{}", headers, "POST")
            assert status == 200 and confirmation["status"] == "UPLOADED", "Real S3 upload confirmation failed"
        if options.verify_restart:
            compose("restart", "video")
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                try:
                    if request(API + "/v1/videos/session", headers=headers)[0] == 200:
                        break
                except urllib.error.URLError:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("Video did not recover after restart")
        status, replay = request(API + "/v1/videos/uploads", data, headers, "POST")
        if options.complete:
            assert status == 409, "Confirmed upload must not receive another PUT URL"
            status, completed_replay = request(API + "/v1/videos/" + upload_id + "/complete", b"{}", headers, "POST")
            assert status == 200 and completed_replay == confirmation, "Completion retry changed persisted metadata"
        else:
            assert status == 200 and replay["id"] == upload_id, "Retry did not preserve the pending video"
        count = compose("exec", "-T", "video-db", "psql", "-U", "youscout_video", "-d", "youscout_video", "-Atc",
                        f"SELECT count(*) FROM videos WHERE id = '{upload_id}' AND idempotency_key = '{key}'")
        assert count == "1", "Expected exactly one persistent video"
        if options.verify_outbox:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                published = compose("exec", "-T", "video-db", "psql", "-U", "youscout_video", "-d", "youscout_video", "-Atc",
                    f"SELECT count(*) FROM outbox_events WHERE aggregate_id = '{upload_id}' AND event_type = 'VideoUploaded' "
                    f"AND payload->'data'->>'videoId' = '{upload_id}' AND published_at IS NOT NULL")
                if published == "1":
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Outbox event was not published; check the enabled relay and Kafka.")
            print("Real S3 confirmation produced exactly one acknowledged VideoUploaded outbox event.")
        print("Upload ticket, signature and idempotent persistence verified" + (" after restart." if options.verify_restart else "."))
        if options.put_object:
            print("Real signed S3 PUT succeeded; this does not validate video content.")
        if options.complete:
            print("Real S3 HEAD confirmation and its persistent idempotent replay succeeded.")
    finally:
        if attempted_put and object_key:
            result = subprocess.run([options.aws, "s3api", "delete-object", "--bucket", BUCKET, "--key", object_key,
                                     "--profile", "youscout-terraform", "--region", "us-east-1", "--no-cli-pager"], capture_output=True)
            if result.returncode:
                raise RuntimeError("Temporary S3 object cleanup failed; inspect the latest smoke fixture.")
        compose("exec", "-T", "video-db", "psql", "-U", "youscout_video", "-d", "youscout_video", "-c",
                f"DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id FROM videos WHERE idempotency_key = '{key}' AND title = 'Temporary upload smoke')")
        compose("exec", "-T", "video-db", "psql", "-U", "youscout_video", "-d", "youscout_video", "-c",
                f"DELETE FROM videos WHERE idempotency_key = '{key}' AND title = 'Temporary upload smoke'")
        print("Temporary smoke fixtures removed; no tokens or signed URLs logged.")


if __name__ == "__main__":
    main()
