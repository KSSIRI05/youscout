package ma.youscout.mediaworker;

import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ExecutionStore {
    record Job(UUID eventId, UUID videoId, UUID attemptId, int attempts, String bucket, String key, String etag, long size) {
        String prefix() { return "processed/" + videoId + "/" + attemptId + "/"; }
    }
    record Output(long durationMillis, int width, int height) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();
    ExecutionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 10)
    public Optional<Job> claim() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT event_id FROM processing_jobs WHERE
                    (status = 'QUEUED' AND available_at <= clock_timestamp()) OR
                    (status = 'PROCESSING' AND lease_until < clock_timestamp())
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
            ) UPDATE processing_jobs j SET status = 'PROCESSING', attempt_id = ?, attempts = attempts + 1,
                lease_until = clock_timestamp() + interval '10 minutes', updated_at = clock_timestamp()
            FROM candidate c WHERE j.event_id = c.event_id RETURNING j.*
            """, (r, n) -> new Job(r.getObject("event_id", UUID.class), r.getObject("video_id", UUID.class),
                r.getObject("attempt_id", UUID.class), r.getInt("attempts"), r.getString("s3_bucket"),
                r.getString("s3_key"), r.getString("source_etag"), r.getLong("size_bytes")), UUID.randomUUID()).stream().findFirst();
    }

    @Transactional(timeout = 10)
    public boolean finish(Job job, Output output, ProcessingFailure failure) {
        // Fencing : une tentative expirée ne peut plus modifier le résultat ni produire d'événement.
        var owned = jdbc.queryForList("""
            SELECT event_id FROM processing_jobs WHERE event_id = ? AND attempt_id = ?
              AND status = 'PROCESSING' AND lease_until > clock_timestamp() FOR UPDATE
            """, UUID.class, job.eventId(), job.attemptId());
        if (owned.isEmpty()) return false;
        if (failure != null && failure.retryable && job.attempts() < 3) {
            jdbc.update("""
                UPDATE processing_jobs SET status = 'QUEUED', lease_until = NULL, attempt_id = NULL,
                    error_code = ?, available_at = clock_timestamp() + (? * interval '10 seconds'), updated_at = clock_timestamp()
                WHERE event_id = ?
                """, failure.code, job.attempts(), job.eventId());
            return true;
        }
        String type = output != null ? "VideoProcessed" : "VideoProcessingFailed";
        String videoKey = output == null ? null : job.prefix() + "video.mp4";
        String thumbnailKey = output == null ? null : job.prefix() + "thumbnail.jpg";
        jdbc.update("""
            UPDATE processing_jobs SET status = ?, lease_until = NULL, error_code = ?, output_video_key = ?,
                thumbnail_key = ?, duration_millis = ?, width = ?, height = ?, updated_at = clock_timestamp()
            WHERE event_id = ?
            """, output == null ? "FAILED" : "SUCCEEDED", failure == null ? null : failure.code,
                videoKey, thumbnailKey, output == null ? null : output.durationMillis(),
                output == null ? null : output.width(), output == null ? null : output.height(), job.eventId());
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("videoId", job.videoId()); data.put("sourceEventId", job.eventId());
        data.put("attemptId", job.attemptId()); data.put("sourceETag", job.etag()); data.put("s3Bucket", job.bucket());
        if (output != null) {
            data.put("videoKey", videoKey); data.put("thumbnailKey", thumbnailKey);
            data.put("durationMillis", output.durationMillis()); data.put("width", output.width()); data.put("height", output.height());
        } else data.put("errorCode", failure.code);
        String payload = json.writeValueAsString(Map.of("eventId", eventId, "eventType", type, "eventVersion", 1,
                "producer", "media-worker", "occurredAt", java.time.Instant.now().toString(), "data", data));
        jdbc.update("INSERT INTO worker_outbox(event_id, source_event_id, video_id, payload) VALUES (?, ?, ?, ?::jsonb)",
                eventId, job.eventId(), job.videoId(), payload);
        return true;
    }
}
