package ma.youscout.video;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ProcessingResultStore {
    private final JdbcTemplate jdbc;
    ProcessingResultStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 10)
    public void accept(ProcessingResult result, String topic, int partition, long offset) {
        var rows = jdbc.queryForList("""
            SELECT v.* FROM videos v JOIN outbox_events e ON e.aggregate_id = v.id
            WHERE v.id = ? AND e.id = ? AND e.event_type = 'VideoUploaded' FOR UPDATE OF v
            """, result.videoId(), result.sourceEventId());
        if (rows.isEmpty()) { reject(topic, partition, offset, "UNKNOWN_SOURCE"); return; }
        var seen = jdbc.queryForList("SELECT fingerprint FROM processing_result_inbox WHERE event_id = ?", String.class, result.eventId());
        if (!seen.isEmpty()) {
            if (!seen.getFirst().equals(result.fingerprint())) reject(topic, partition, offset, "EVENT_ID_CONFLICT");
            return;
        }
        var video = rows.getFirst();
        if (!"UPLOADED".equals(video.get("status")) || !result.etag().equals(video.get("source_etag"))
                || !result.bucket().equals(video.get("s3_bucket"))) { reject(topic, partition, offset, "SOURCE_MISMATCH"); return; }
        if ("READY".equals(video.get("processing_status")) || "FAILED".equals(video.get("processing_status"))) {
            reject(topic, partition, offset, "ALREADY_FINALIZED"); return;
        }
        jdbc.update("""
            UPDATE videos SET processing_status = ?, processing_updated_at = clock_timestamp(), processing_error = ?,
                processed_video_key = ?, thumbnail_key = ?, duration_millis = ?, width = ?, height = ? WHERE id = ?
            """, result.success() ? "READY" : "FAILED", result.error(), result.videoKey(), result.thumbnailKey(),
            result.durationMillis(), result.width(), result.height(), result.videoId());
        jdbc.update("INSERT INTO processing_result_inbox(event_id, video_id, fingerprint) VALUES (?, ?, ?)",
            result.eventId(), result.videoId(), result.fingerprint());
    }
    @Transactional(timeout = 10)
    public void reject(String topic, int partition, long offset, String reason) {
        jdbc.update("INSERT INTO rejected_processing_results(topic, partition_id, record_offset, reason) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            topic, partition, offset, reason);
    }
}
