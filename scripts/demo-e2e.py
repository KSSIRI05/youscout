"""Démonstration de bout en bout du MVP sur la pile locale complète, avec le vrai bucket S3.

Parcours : connexion -> upload S3 -> transcodage FFmpeg -> publication -> fil -> like et commentaire
-> arrêt du worker et rejeu des événements (aucun doublon) -> suppression -> retrait du fil et de S3.

Prérequis : session AWS du projet valide et pile démarrée avec les profils video, worker et feed et les
extensions de traitement S3 (voir docs/DEMO.md). Les données créées sont supprimées à la fin, même en cas
d'échec. Aucun jeton, URL signée ni identifiant AWS n'est affiché.
"""
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from keycloak_admin import local_values, ROOT

API = "http://localhost:8080"
COMPOSE = ["docker", "compose", "-f", "infra/compose/compose.yml", "-f", "infra/compose/compose.kafka.yml",
           "-f", "infra/compose/compose.worker.yml", "-f", "infra/compose/compose.processing.yml",
           "-f", "infra/compose/compose.video-aws.yml", "-f", "infra/compose/compose.processing-aws.yml",
           "--profile", "video", "--profile", "worker", "--profile", "feed"]
KAFKA = "/opt/kafka/bin/"
WORKER_GROUP, FEED_GROUP = "youscout-media-worker-v1", "youscout-feed-v2"


def step(text):
    print(f"\n== {text}", flush=True)


def ok(text):
    print(f"PASS: {text}", flush=True)


def run(args, stdin=None, timeout=60, check=True):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, cwd=ROOT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed: {' '.join(args[:3])} … (output omitted)")
    return result.stdout.strip()


def compose(*args, **kwargs):
    return run([*COMPOSE, *args], **kwargs)


def sql(service, database, statement):
    return compose("exec", "-T", service, "psql", "-v", "ON_ERROR_STOP=1", "-U", database, "-d", database, "-At", stdin=statement)


def mongo(expression):
    return compose("exec", "-T", "video-mongo", "mongosh", "mongodb://localhost:27017/youscout_video?replicaSet=rs0",
                   "--quiet", "--eval", expression)


def http(method, path, token, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(API + path, data=data, method=method,
                                     headers={"Authorization": "Bearer " + token, **({"Content-Type": "application/json"} if data else {}), **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


def wait(predicate, what, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for: {what}")


def in_feed(token, video_id):
    status, page = http("GET", "/v1/feed?offset=0", token)
    return status == 200 and sum(item["videoId"] == video_id for item in page["items"])


def kafka_records(topic, key):
    """Lit un topic depuis le début et renvoie les messages dont la clé correspond."""
    output = compose("exec", "-T", "kafka", KAFKA + "kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                     "--topic", topic, "--from-beginning", "--timeout-ms", "8000", "--property", "print.key=true", check=False)
    return [payload for record_key, _, payload in (line.partition("\t") for line in output.splitlines()) if record_key == key]


def replay(topic, key, payload):
    compose("exec", "-T", "kafka", KAFKA + "kafka-console-producer.sh", "--bootstrap-server", "kafka:9092", "--topic", topic,
            "--property", "parse.key=true", "--property", "key.separator=\t", stdin=f"{key}\t{payload}\n")


def lag(group):
    """Messages pas encore traités par un groupe ; une partition sans offset commité compte depuis 0 (vide = 0)."""
    output = compose("exec", "-T", "kafka", KAFKA + "kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092",
                     "--describe", "--group", group, check=False, timeout=60)
    rows = [line.split() for line in output.splitlines() if line.startswith(group + " ")]
    if not rows:
        return None
    return sum(int(end) - (0 if current == "-" else int(current)) for _, _, _, current, end, *_ in rows)


def worker_state(video_id):
    return sql("worker-db", "youscout_media_worker", f"""
        SELECT (SELECT count(*) FROM processing_jobs WHERE video_id = '{video_id}') || ',' ||
               (SELECT coalesce(string_agg(status, ''), '') FROM processing_jobs WHERE video_id = '{video_id}') || ',' ||
               (SELECT count(*) FROM processing_attempts WHERE video_id = '{video_id}') || ',' ||
               (SELECT count(*) FROM worker_outbox WHERE video_id = '{video_id}');""")


def main():
    values = local_values()
    aws = shutil.which("aws") or str(Path.home() / "AppData/Local/Programs/Amazon/AWSCLIV2/aws.exe")
    bucket = values.get("VIDEO_S3_BUCKET", "youscout-014663857971-dev-videos")
    aws_base = [aws, "--profile", "youscout-terraform", "--region", values.get("VIDEO_S3_REGION", "us-east-1"), "--no-cli-pager"]
    video_id = None
    idempotency = str(uuid.uuid4())

    step("1. Connexion (compte technique Keycloak, rôle user)")
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "youscout-smoke",
                                   "client_secret": values["SMOKE_CLIENT_SECRET"]}).encode()
    with urllib.request.urlopen(urllib.request.Request("http://localhost:8180/realms/youscout/protocol/openid-connect/token", form), timeout=20) as r:
        token = json.load(r)["access_token"]
    status, profile = http("GET", "/v1/users/me", token)
    assert status == 200, "Profile unavailable"
    ok(f"token issued by Keycloak; profile {profile['subject'][:8]}… created or read through the Gateway.")

    try:
        step("2. Upload d'un clip vers le bucket S3 privé")
        with tempfile.TemporaryDirectory(prefix="youscout-demo-") as directory:
            clip = Path(directory) / "clip.mp4"
            run([shutil.which("ffmpeg") or "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                 "testsrc2=size=320x240:rate=24", "-t", "3", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", str(clip)])
            status, ticket = http("POST", "/v1/videos/uploads", token,
                                  {"title": "Démo YouScout", "contentType": "video/mp4", "sizeBytes": clip.stat().st_size},
                                  {"Idempotency-Key": idempotency})
            assert status == 201, f"Upload ticket refused ({status})"
            video_id = str(uuid.UUID(ticket["id"]))
            try:
                put = urllib.request.Request(ticket["upload"]["url"], data=clip.read_bytes(), headers=ticket["upload"]["headers"], method="PUT")
                with urllib.request.urlopen(put, timeout=30) as response:
                    assert response.status == 200
            except Exception:
                raise RuntimeError("S3 PUT failed (signed URL omitted).") from None
        status, confirmation = http("POST", f"/v1/videos/{video_id}/complete", token)
        assert status == 200 and confirmation["status"] == "UPLOADED"
        ok(f"video {video_id[:8]}… uploaded with a signed PUT and confirmed (UPLOADED).")

        step("3. Transcodage par le worker (FFmpeg) et retour du résultat via Kafka")
        def ready():
            status, details = http("GET", f"/v1/videos/{video_id}", token)
            if status == 200 and details["processingStatus"] == "FAILED":
                raise RuntimeError(f"Processing failed: {details['errorCode']}")
            return status == 200 and details["processingStatus"] == "READY"
        wait(ready, "processing READY")
        _, details = http("GET", f"/v1/videos/{video_id}", token)
        ok(f"READY: {details['width']}x{details['height']}, {details['durationMillis']} ms; MP4 and thumbnail written to S3.")

        step("4. Publication et apparition dans le fil")
        status, publication = http("POST", f"/v1/videos/{video_id}/publish", token)
        assert status == 200 and publication["publicationStatus"] == "PUBLISHED"
        wait(lambda: in_feed(token, video_id) == 1, "video in the feed", timeout=60)
        assert http("GET", f"/v1/videos/{video_id}/public-playback", token)[0] == 200
        ok("published; Feed received VideoPublished and lists the video; public playback links are issued.")

        step("5. Interactions : like et commentaire")
        status, likes = http("PUT", f"/v1/social/videos/{video_id}/likes", token)
        assert status == 200 and likes == {"count": 1, "liked": True}
        status, comment = http("POST", f"/v1/social/videos/{video_id}/comments", token, {"body": "Superbe contrôle !"})
        assert status == 200
        status, comments = http("GET", f"/v1/social/videos/{video_id}/comments", token)
        assert [c["id"] for c in comments] == [comment["id"]]
        ok("like counted once and comment stored by Social (video checked with Video).")

        step("6. Robustesse : arrêt du worker, rejeu des événements, aucun doublon")
        uploaded = kafka_records("video.uploaded.v1", video_id)
        published = kafka_records("video.published.v2", video_id)
        assert len(uploaded) == 1 and len(published) == 1, "Original events not found in Kafka"
        before_worker = worker_state(video_id)
        before_video = mongo(f'const v = db.videos.findOne({{_id: "{video_id}"}}); print(v.processingStatus + "|" + v.processingUpdatedAt.toISOString());')
        compose("stop", "media-worker")
        replay("video.uploaded.v1", video_id, uploaded[0])
        replay("video.published.v2", video_id, published[0])
        ok("worker stopped; VideoUploaded and VideoPublished re-sent to Kafka with their original eventId.")
        compose("start", "media-worker")
        wait(lambda: lag(WORKER_GROUP) == 0, "worker caught up after restart")
        wait(lambda: lag(FEED_GROUP) == 0, "feed caught up")
        assert worker_state(video_id) == before_worker, "Worker state changed after the replay"
        assert worker_state(video_id).split(",")[:2] == ["1", "SUCCEEDED"]
        after_video = mongo(f'const v = db.videos.findOne({{_id: "{video_id}"}}); print(v.processingStatus + "|" + v.processingUpdatedAt.toISOString());')
        assert after_video == before_video, "Video processing state changed after the replay"
        assert sql("feed-db", "youscout_feed", f"SELECT count(*) FROM feed_entries WHERE video_id = '{video_id}';") == "1"
        assert in_feed(token, video_id) == 1
        ok("after restart: still one job (SUCCEEDED), no new attempt or result, Video state unchanged, one feed entry.")

        step("7. Suppression par le propriétaire")
        assert http("DELETE", f"/v1/videos/{video_id}", token)[0] == 204
        assert http("DELETE", f"/v1/videos/{video_id}", token)[0] == 204
        assert http("GET", f"/v1/videos/{video_id}", token)[0] == 404
        wait(lambda: in_feed(token, video_id) == 0, "video removed from the feed", timeout=60)
        assert http("PUT", f"/v1/social/videos/{video_id}/likes", token)[0] == 404
        wait(lambda: sql("worker-db", "youscout_media_worker",
                         f"SELECT status FROM video_deletions WHERE video_id = '{video_id}';") == "DONE", "S3 cleanup", timeout=120)
        keys = [f"videos/{video_id}/source"] + [f"processed/{video_id}/{attempt}/{name}"
                for attempt in sql("worker-db", "youscout_media_worker",
                                   f"SELECT attempt_id FROM processing_attempts WHERE video_id = '{video_id}';").split()
                for name in ("video.mp4", "thumbnail.jpg")]
        for key in keys:
            head = subprocess.run([*aws_base, "s3api", "head-object", "--bucket", bucket, "--key", key], capture_output=True, text=True, cwd=ROOT, timeout=30)
            assert head.returncode != 0 and ("404" in head.stderr or "Not Found" in head.stderr), "S3 object still present after deletion"
        ok(f"deleted (repeat returns 204), gone from the library and the feed, new likes refused, {len(keys)} S3 objects removed by the worker.")
        print("\nDEMO COMPLETE: every step of the MVP flow passed.", flush=True)
    finally:
        if video_id:
            uuid.UUID(video_id)
            # Les objets S3 restants (si une étape a échoué avant la suppression) sont retirés, clés vérifiées.
            listed = run([*aws_base, "s3api", "list-objects-v2", "--bucket", bucket, "--prefix", f"processed/{video_id}/",
                          "--query", "Contents[].Key", "--output", "json"], check=False)
            leftovers = [f"videos/{video_id}/source", *(json.loads(listed or "null") or [])]
            for key in leftovers:
                if key.startswith("processed/") and not re.fullmatch(rf"processed/{video_id}/[0-9a-f-]{{36}}/(video\.mp4|thumbnail\.jpg)", key):
                    raise RuntimeError("Cleanup refused an unexpected S3 key.")
                run([*aws_base, "s3api", "delete-object", "--bucket", bucket, "--key", key], check=False)
            mongo(f'db.videos.deleteOne({{_id: "{video_id}", idempotencyKey: "{idempotency}"}}); db.video_outbox.deleteMany({{aggregateId: "{video_id}"}}); '
                  f'db.processing_result_inbox.deleteMany({{videoId: "{video_id}"}}); print("ok");')
            sql("worker-db", "youscout_media_worker", f"DELETE FROM video_deletions WHERE video_id = '{video_id}'; "
                f"DELETE FROM processing_attempts WHERE video_id = '{video_id}'; DELETE FROM processing_jobs WHERE video_id = '{video_id}';")
            sql("feed-db", "youscout_feed", f"DELETE FROM feed_entries WHERE video_id = '{video_id}'; "
                f"DELETE FROM feed_deleted_videos WHERE video_id = '{video_id}'; DELETE FROM feed_event_inbox WHERE video_id = '{video_id}';")
            sql("social-db", "youscout_social", f"DELETE FROM video_likes WHERE video_id = '{video_id}'; "
                f"DELETE FROM video_comments WHERE video_id = '{video_id}';")
            print("Demo data removed from MongoDB, PostgreSQL and S3; Kafka keeps its records until retention expires.", flush=True)


if __name__ == "__main__":
    main()
