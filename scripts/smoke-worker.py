"""Verify durable worker ingestion and duplicate delivery across a restart; no S3 access."""
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ["docker", "compose", "-f", "infra/compose/compose.yml", "-f", "infra/compose/compose.kafka.yml",
        "-f", "infra/compose/compose.worker.yml", "--profile", "video", "--profile", "worker"]
GROUP = "youscout-media-worker-v1"


def command(*args, stdin=None):
    result = subprocess.run([*BASE, *args], input=stdin, cwd=ROOT, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Worker smoke operation failed; inspect service health (payload omitted).")
    return result.stdout.strip()


def sql(statement):
    return command("exec", "-T", "worker-db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "youscout_media_worker",
                   "-d", "youscout_media_worker", "-At", stdin=statement)


def wait_for(condition):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(1)
    raise RuntimeError("Worker smoke timed out waiting for durable ingestion.")


def caught_up():
    output = command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server",
                     "kafka:9092", "--group", GROUP, "--describe")
    rows = [line.split() for line in output.splitlines() if len(line.split()) >= 6 and line.split()[1] == "video.uploaded.v1"]
    return len(rows) == 3 and all(row[5] == "0" for row in rows)


def main():
    event_id, video_id = str(uuid.uuid4()), str(uuid.uuid4())
    event = {"eventId": event_id, "eventType": "VideoUploaded", "eventVersion": 1,
             "occurredAt": "2026-10-29T12:00:00Z", "producer": "video-service", "data": {
                 "videoId": video_id, "s3Bucket": "test-bucket", "s3Key": f"videos/{video_id}/source",
                 "sourceETag": "synthetic-etag", "contentType": "video/mp4", "sizeBytes": 12}}
    wire = video_id + "|" + json.dumps(event, separators=(",", ":")) + "\n"

    def send():
        command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
                "--topic", "video.uploaded.v1", "--property", "parse.key=true", "--property", "key.separator=|", stdin=wire)

    try:
        send()
        wait_for(lambda: sql(f"SELECT count(*) FROM processing_jobs WHERE event_id = '{event_id}' AND status = 'QUEUED';") == "1")
        original = sql(f"SELECT created_at::text || '|' || updated_at::text FROM processing_jobs WHERE event_id = '{event_id}';")
        send()
        wait_for(caught_up)
        command("restart", "media-worker")
        send()
        wait_for(caught_up)
        assert sql(f"SELECT count(*) FROM processing_jobs WHERE event_id = '{event_id}' OR video_id = '{video_id}';") == "1"
        assert sql(f"SELECT created_at::text || '|' || updated_at::text FROM processing_jobs WHERE event_id = '{event_id}';") == original
        print("PASS: three deliveries across worker restart produced exactly one unchanged QUEUED job.", flush=True)
    finally:
        sql(f"DELETE FROM processing_jobs WHERE event_id = '{event_id}' AND video_id = '{video_id}';")
        print("Own synthetic job removed; Kafka test records expire with topic retention.", flush=True)


if __name__ == "__main__":
    main()
