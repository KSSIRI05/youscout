"""Exercise the running relay with a synthetic DB fixture, broker outage and Video restart.

No S3 access or user login is required. This checks relay wiring; HTTP completion is
covered by Java integration tests. Only this script's DB fixtures are removed.
"""
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ["docker", "compose", "-f", "infra/compose/compose.yml", "-f", "infra/compose/compose.kafka.yml", "--profile", "video"]


def command(*args, stdin=None, check=True):
    result = subprocess.run([*BASE, *args], cwd=ROOT, input=stdin, capture_output=True, text=True, timeout=30)
    if check and result.returncode:
        raise RuntimeError("Compose outbox operation failed; inspect service health (payload omitted).")
    return result


def sql(statement):
    return command("exec", "-T", "video-db", "psql", "-v", "ON_ERROR_STOP=1", "-U", "youscout_video",
                   "-d", "youscout_video", "-At", stdin=statement).stdout.strip()


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
        # UUIDs are generated here; no caller input is interpolated into this fixture SQL.
        sql(f"""BEGIN;
            INSERT INTO videos(id, owner_subject, title, s3_bucket, s3_key, content_type, size_bytes,
                idempotency_key, status, completed_at, source_etag)
            VALUES ('{video_id}', 'outbox-smoke', 'Temporary outbox smoke', 'test-bucket',
                'videos/{video_id}/source', 'video/mp4', 12, '{key}', 'UPLOADED', statement_timestamp(), 'synthetic-etag');
            INSERT INTO outbox_events(id, aggregate_id, event_type, payload)
            SELECT '{event_id}', id, 'VideoUploaded', jsonb_build_object(
                'eventId', '{event_id}', 'eventType', 'VideoUploaded', 'eventVersion', 1,
                'occurredAt', completed_at, 'producer', 'video-service',
                'data', jsonb_build_object('videoId', id, 's3Bucket', s3_bucket, 's3Key', s3_key,
                    'sourceETag', source_etag, 'contentType', content_type, 'sizeBytes', size_bytes))
            FROM videos WHERE id = '{video_id}';
            COMMIT;""")
        wait_for(lambda: sql(f"SELECT attempts > 0 AND published_at IS NULL FROM outbox_events WHERE id = '{event_id}';") == "t")
        print("PASS: broker outage retains the pending event with a recorded retry.", flush=True)
        command("restart", "video")
        assert sql(f"SELECT count(*) FROM outbox_events WHERE id = '{event_id}' AND published_at IS NULL;") == "1"
        command("unpause", "kafka")
        paused = False
        wait_for(lambda: sql(f"SELECT published_at IS NOT NULL FROM outbox_events WHERE id = '{event_id}';") == "t")
        result = command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
                         "--bootstrap-server", "kafka:9092", "--topic", "video.uploaded.v1", "--from-beginning",
                         "--timeout-ms", "5000", "--property", "print.key=true", check=False)
        matches = []
        for line in result.stdout.splitlines():
            record_key, separator, payload = line.partition("\t")
            if separator and record_key == video_id:
                matches.append(json.loads(payload))
        assert matches and all(event["eventId"] == event_id and event["data"]["videoId"] == video_id for event in matches)
        assert sql(f"SELECT status FROM videos WHERE id = '{video_id}';") == "UPLOADED"
        print("PASS: after Video restart and broker recovery, Kafka contains the original eventId and videoId key.", flush=True)
    finally:
        if paused:
            command("unpause", "kafka")
        sql(f"""BEGIN;
            DELETE FROM outbox_events WHERE id = '{event_id}' AND aggregate_id = '{video_id}';
            DELETE FROM videos WHERE id = '{video_id}' AND idempotency_key = '{key}';
            COMMIT;""")
        print("Synthetic DB fixtures removed; Kafka retains its synthetic test record until topic retention expires.", flush=True)


if __name__ == "__main__":
    main()
