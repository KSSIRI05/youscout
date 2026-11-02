"""Exercise the running MongoDB outbox across a broker outage and Video restart.

No S3 access or user login is required. Only this script's MongoDB fixtures are removed.
"""
import json
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
             "--profile", "video"])


def command(*args, check=True):
    result = subprocess.run([*BASE, *args], cwd=ROOT, capture_output=True, text=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError("Compose outbox operation failed; inspect service health (payload omitted).")
    return result


def mongo_eval(expression):
    return command("exec", "-T", "video-mongo", "mongosh",
                   "mongodb://localhost:27017/youscout_video?replicaSet=rs0", "--quiet", "--eval",
                   expression).stdout.strip()


def wait_for(predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError("Outbox verification timed out; inspect broker and Video health.")


def main():
    video_id, event_id, key = (str(uuid.uuid4()) for _ in range(3))
    paused = False
    try:
        command("pause", "kafka")
        paused = True
        fixture = f'''db.videos.insertOne({{
          _id: "{video_id}", id: "{video_id}", ownerSubject: "outbox-smoke",
          title: "Temporary outbox smoke", s3Bucket: "test-bucket",
          s3Key: "videos/{video_id}/source", contentType: "video/mp4", sizeBytes: 12,
          idempotencyKey: "{key}", status: "UPLOADED", completedAt: new Date(),
          sourceETag: "synthetic-etag", publicationStatus: "UNPUBLISHED", skills: [], hashtags: []
        }});
        const occurredAt = new Date().toISOString();
        db.video_outbox.insertOne({{
          _id: "{event_id}", id: "{event_id}", aggregateId: "{video_id}", eventType: "VideoUploaded",
          payload: {{ eventId: "{event_id}", eventType: "VideoUploaded", eventVersion: 1,
            occurredAt, producer: "video-service", data: {{ videoId: "{video_id}",
              s3Bucket: "test-bucket", s3Key: "videos/{video_id}/source", sourceETag: "synthetic-etag",
              contentType: "video/mp4", sizeBytes: 12 }} }},
          createdAt: new Date(), publishedAt: null, attempts: 0, nextAttemptAt: new Date(), lastError: null
        }});
        print("fixture-created");'''
        assert mongo_eval(fixture) == "fixture-created"
        wait_for(lambda: mongo_eval(
            f'''const e = db.video_outbox.findOne({{_id: "{event_id}"}});
                print(e && e.attempts > 0 && !e.publishedAt ? "true" : "false");''') == "true")
        print("PASS: broker outage retains the pending event with a recorded retry.", flush=True)
        command("restart", "video")
        assert mongo_eval(f'''print(db.video_outbox.countDocuments({{_id: "{event_id}", publishedAt: null}}));''') == "1"
        command("unpause", "kafka")
        paused = False
        wait_for(lambda: command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
                                 "--bootstrap-server", "kafka:9092", "--list", check=False).returncode == 0,
                 timeout=30)
        try:
            wait_for(lambda: mongo_eval(
                f'''const e = db.video_outbox.findOne({{_id: "{event_id}"}});
                    print(e && e.publishedAt ? "true" : "false");''') == "true", timeout=90)
        except RuntimeError as failure:
            state = mongo_eval(
                f'''const e = db.video_outbox.findOne({{_id: "{event_id}"}});
                    print(e ? JSON.stringify({{attempts:e.attempts, published:!!e.publishedAt,
                      nextAttemptAt:e.nextAttemptAt, locked:!!e.lockedUntil, lastError:e.lastError}}) : "missing");''')
            raise RuntimeError(f"{failure} Final outbox state: {state}") from failure
        result = command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
                         "--bootstrap-server", "kafka:9092", "--topic", "video.uploaded.v1", "--from-beginning",
                         "--timeout-ms", "5000", "--property", "print.key=true", check=False)
        matches = []
        for line in result.stdout.splitlines():
            record_key, separator, payload = line.partition("\t")
            if separator and record_key == video_id:
                matches.append(json.loads(payload))
        assert matches and all(event["eventId"] == event_id and event["data"]["videoId"] == video_id
                               for event in matches)
        assert mongo_eval(f'''print(db.videos.findOne({{_id: "{video_id}"}}).status);''') == "UPLOADED"
        print("PASS: after Video restart and broker recovery, Kafka contains the original eventId and videoId key.", flush=True)
    finally:
        if paused:
            command("unpause", "kafka")
        mongo_eval(f'''db.video_outbox.deleteOne({{_id: "{event_id}", aggregateId: "{video_id}"}});
                      db.videos.deleteOne({{_id: "{video_id}", idempotencyKey: "{key}"}});
                      print("fixtures-removed");''')
        print("Synthetic MongoDB fixtures removed; Kafka retains its synthetic test record until topic retention expires.", flush=True)


if __name__ == "__main__":
    main()
