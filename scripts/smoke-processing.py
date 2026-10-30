"""Real S3 -> FFmpeg -> Kafka result -> Video test. Removes only generated fixtures.

Requires valid local Video/Terraform AWS profiles and the processing Compose extensions.
Never logs a Bearer, signed URL, CLI credential or raw SDK error.
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

BASE = ["docker", "compose", "-f", "infra/compose/compose.yml", "-f", "infra/compose/compose.kafka.yml",
        "-f", "infra/compose/compose.worker.yml", "-f", "infra/compose/compose.processing.yml", "--profile", "video", "--profile", "worker"]


def command(args, stdin=None):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, cwd=ROOT, timeout=30)
    if result.returncode:
        raise RuntimeError("Processing smoke operation failed; inspect service health or AWS session.")
    return result.stdout.strip()


def sql(service, database, statement):
    return command([*BASE, "exec", "-T", service, "psql", "-v", "ON_ERROR_STOP=1", "-U", database, "-d", database, "-At"], statement)


def request(url, data=None, headers=None, method=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers or {}, method=method), timeout=20) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        return error.code, None


def main():
    values = local_values()
    aws = shutil.which("aws") or str(Path.home() / "AppData/Local/Programs/Amazon/AWSCLIV2/aws.exe")
    bucket = values.get("VIDEO_S3_BUCKET", "youscout-014663857971-dev-videos")
    region = values.get("VIDEO_S3_REGION", "us-east-1")
    key, video_id = str(uuid.uuid4()), None
    aws_base = [aws, "--profile", "youscout-terraform", "--region", region, "--no-cli-pager"]
    form = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "youscout-smoke", "client_secret": values["SMOKE_CLIENT_SECRET"]}).encode()
    status, tokens = request("http://localhost:8180/realms/youscout/protocol/openid-connect/token", form, {"Content-Type": "application/x-www-form-urlencoded"})
    assert status == 200, "Technical identity unavailable"
    headers = {"Authorization": "Bearer " + tokens["access_token"], "Content-Type": "application/json", "Idempotency-Key": key}
    try:
        with tempfile.TemporaryDirectory(prefix="youscout-processing-smoke-") as directory:
            clip = Path(directory) / "source.mp4"
            result = subprocess.run([shutil.which("ffmpeg") or "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=320x240:rate=24", "-t", "2", "-an", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p", "-y", str(clip)], capture_output=True, timeout=20)
            assert result.returncode == 0, "Synthetic clip creation failed"
            body = json.dumps({"title": "Temporary processing smoke", "contentType": "video/mp4", "sizeBytes": clip.stat().st_size}).encode()
            status, ticket = request("http://localhost:8080/v1/videos/uploads", body, headers, "POST")
            assert status == 201, "Upload ticket unavailable; check S3 identity"
            video_id = str(uuid.UUID(ticket["id"]))
            try:
                with urllib.request.urlopen(urllib.request.Request(ticket["upload"]["url"], data=clip.read_bytes(), headers=ticket["upload"]["headers"], method="PUT"), timeout=30) as uploaded:
                    assert uploaded.status == 200
            except Exception:
                raise RuntimeError("Source PUT failed; signed URL omitted.") from None
            status, confirmation = request(f"http://localhost:8080/v1/videos/{video_id}/complete", b"{}", headers, "POST")
            assert status == 200 and confirmation["status"] == "UPLOADED", "Source confirmation failed"
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                status, details = request(f"http://localhost:8080/v1/videos/{video_id}", headers=headers)
                if status == 200 and details["processingStatus"] == "READY":
                    break
                if status == 200 and details["processingStatus"] == "FAILED":
                    raise RuntimeError("Processing finished with a controlled failure code; inspect worker job state.")
                time.sleep(1)
            else:
                raise RuntimeError("Processing smoke timed out waiting for Video READY.")
            assert details["durationMillis"] > 0 and details["width"] > 0 and details["height"] > 0
            assert sql("worker-db", "youscout_media_worker", f"SELECT count(*) FROM processing_jobs WHERE video_id = '{video_id}' AND status = 'SUCCEEDED';") == "1"
            replay_status, replay = request(f"http://localhost:8080/v1/videos/{video_id}/complete", b"{}", headers, "POST")
            assert replay_status == 200 and replay == confirmation, "Completion replay changed after processing"
            print("PASS: real S3 conditional download, FFmpeg MP4/thumbnail, worker outbox, Kafka and Video READY.", flush=True)
            print("PASS: private status metadata and stable upload confirmation after processing.", flush=True)
    finally:
        if video_id:
            prefix = f"processed/{video_id}/"
            keys = json.loads(command([*aws_base, "s3api", "list-objects-v2", "--bucket", bucket, "--prefix", prefix, "--query", "Contents[].Key", "--output", "json"])) or []
            for object_key in keys:
                if not re.fullmatch(re.escape(prefix) + r"[0-9a-f-]{36}/(?:video\.mp4|thumbnail\.jpg)", object_key):
                    raise RuntimeError("Cleanup refused an unexpected derived fixture key.")
            for object_key in [f"videos/{video_id}/source", *keys]:
                command([*aws_base, "s3api", "delete-object", "--bucket", bucket, "--key", object_key])
            sql("worker-db", "youscout_media_worker", f"DELETE FROM processing_jobs WHERE video_id = '{video_id}';")
            sql("video-db", "youscout_video", f"BEGIN; DELETE FROM processing_result_inbox WHERE video_id = '{video_id}'; DELETE FROM outbox_events WHERE aggregate_id = '{video_id}'; DELETE FROM videos WHERE id = '{video_id}' AND idempotency_key = '{key}'; COMMIT;")
            print("Own source/derived S3 objects and DB fixtures removed; Kafka test messages expire with retention.", flush=True)


if __name__ == "__main__":
    main()
