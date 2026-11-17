"""Check that the running Video relay routes VideoDeleted to its topic and that Feed removes the entry.

Synthetic VideoPublished and VideoDeleted outbox events are written to MongoDB; no S3 access or login
is required. Only this script's fixtures are removed afterwards.
"""
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ["docker", "compose"]
ENV_FILE = ROOT / "infra" / "compose" / ".env"
if ENV_FILE.exists():
    BASE.extend(["--env-file", str(ENV_FILE)])
BASE.extend(["-f", "infra/compose/compose.yml", "-f", "infra/compose/compose.kafka.yml",
             "--profile", "video", "--profile", "feed"])


def command(*args, check=True):
    result = subprocess.run([*BASE, *args], cwd=ROOT, capture_output=True, text=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError("Compose deletion operation failed; inspect service health (payload omitted).")
    return result


def mongo_eval(expression):
    return command("exec", "-T", "video-mongo", "mongosh",
                   "mongodb://localhost:27017/youscout_video?replicaSet=rs0", "--quiet", "--eval",
                   expression).stdout.strip()


def feed_sql(query):
    return command("exec", "-T", "feed-db", "psql", "-U", "youscout_feed", "-d", "youscout_feed",
                   "-tAc", query).stdout.strip()


def wait_for(predicate, message, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError(message)


def outbox_event(event_id, video_id, event_type, version, data):
    return f'''db.video_outbox.insertOne({{
      _id: "{event_id}", id: "{event_id}", aggregateId: "{video_id}", eventType: "{event_type}",
      payload: {{ eventId: "{event_id}", eventType: "{event_type}", eventVersion: {version},
        occurredAt: at, producer: "video-service", data: {data} }},
      createdAt: new Date(), publishedAt: null, attempts: 0, nextAttemptAt: new Date(), lastError: null
    }});'''


def main():
    video_id, published_id, deleted_id = (str(uuid.uuid4()) for _ in range(3))
    owner = "deletion-smoke"
    try:
        published = outbox_event(published_id, video_id, "VideoPublished", 2,
            f'{{ videoId: "{video_id}", ownerSubject: "{owner}", title: "Temporary deletion smoke", '
            f'publishedAt: at, skills: [], hashtags: [] }}')
        assert mongo_eval(f'const at = new Date().toISOString(); {published} print("ok");') == "ok"
        wait_for(lambda: feed_sql(f"SELECT count(*) FROM feed_entries WHERE video_id = '{video_id}'") == "1",
                 "Feed did not project the synthetic VideoPublished event.")
        print("PASS: Feed projected the synthetic publication.", flush=True)

        deleted = outbox_event(deleted_id, video_id, "VideoDeleted", 1,
            f'{{ videoId: "{video_id}", ownerSubject: "{owner}", deletedAt: at, wasPublished: true }}')
        assert mongo_eval(f'const at = new Date().toISOString(); {deleted} print("ok");') == "ok"
        wait_for(lambda: mongo_eval(f'const e = db.video_outbox.findOne({{_id: "{deleted_id}"}}); '
                                    f'print(e && e.publishedAt ? "true" : "false");') == "true",
                 "Video relay did not publish VideoDeleted; check the video.deleted.v1 topic routing.")
        wait_for(lambda: feed_sql(f"SELECT count(*) FROM feed_entries WHERE video_id = '{video_id}'") == "0"
                 and feed_sql(f"SELECT count(*) FROM feed_deleted_videos WHERE video_id = '{video_id}'") == "1",
                 "Feed did not remove the entry after VideoDeleted.")
        print("PASS: VideoDeleted reached Feed through video.deleted.v1 and removed the entry.", flush=True)
    finally:
        mongo_eval(f'''db.video_outbox.deleteMany({{_id: {{$in: ["{published_id}", "{deleted_id}"]}}, aggregateId: "{video_id}"}});
                      print("removed");''')
        feed_sql(f"DELETE FROM feed_entries WHERE video_id = '{video_id}'; "
                 f"DELETE FROM feed_deleted_videos WHERE video_id = '{video_id}'; "
                 f"DELETE FROM feed_event_inbox WHERE video_id = '{video_id}';")
        print("Synthetic outbox and Feed fixtures removed; Kafka keeps its records until retention expires.", flush=True)


if __name__ == "__main__":
    main()
