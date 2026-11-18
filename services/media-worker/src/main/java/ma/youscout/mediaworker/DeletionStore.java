package ma.youscout.mediaworker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Records deletions before the Kafka ACK and hands out the S3 cleanup, which is retried until it succeeds. */
@Repository
class DeletionStore {
    record Cleanup(UUID videoId, List<String> keys) {}
    // Jobs not yet running, or whose lease expired, are cancelled; the fencing in ExecutionStore rejects their late results.
    static final String CANCEL_JOBS = """
        UPDATE processing_jobs SET status = 'FAILED', error_code = 'VIDEO_DELETED', lease_until = NULL,
            updated_at = clock_timestamp()
        WHERE video_id = ? AND (status = 'QUEUED' OR (status = 'PROCESSING' AND lease_until < clock_timestamp()))
          AND EXISTS (SELECT 1 FROM video_deletions d WHERE d.video_id = processing_jobs.video_id)
        """;
    private final JdbcTemplate jdbc;
    DeletionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 10)
    public void accept(VideoDeleted event) {
        // A replay, or another event for an already deleted video, changes nothing.
        jdbc.update("INSERT INTO video_deletions(video_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            event.videoId(), event.eventId());
        jdbc.update(CANCEL_JOBS, event.videoId());
    }

    /** Claims one due cleanup for five minutes; postpones it while a live processing attempt may still upload. */
    @Transactional(timeout = 10)
    public Optional<Cleanup> claim() {
        var due = jdbc.queryForList("""
            SELECT video_id FROM video_deletions WHERE status = 'PENDING' AND available_at <= clock_timestamp()
            ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED
            """, UUID.class);
        if (due.isEmpty()) return Optional.empty();
        UUID videoId = due.getFirst();
        jdbc.update(CANCEL_JOBS, videoId);
        boolean running = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM processing_jobs WHERE video_id = ? AND status = 'PROCESSING')", Boolean.class, videoId));
        if (running) {
            jdbc.update("UPDATE video_deletions SET available_at = clock_timestamp() + interval '30 seconds' WHERE video_id = ?", videoId);
            return Optional.empty();
        }
        jdbc.update("""
            UPDATE video_deletions SET attempts = attempts + 1, available_at = clock_timestamp() + interval '5 minutes'
            WHERE video_id = ?
            """, videoId);
        var keys = new ArrayList<String>();
        keys.add("videos/" + videoId + "/source");
        for (UUID attempt : jdbc.queryForList("SELECT attempt_id FROM processing_attempts WHERE video_id = ? ORDER BY created_at",
                UUID.class, videoId)) {
            keys.add("processed/" + videoId + "/" + attempt + "/video.mp4");
            keys.add("processed/" + videoId + "/" + attempt + "/thumbnail.jpg");
        }
        return Optional.of(new Cleanup(videoId, List.copyOf(keys)));
    }

    @Transactional(timeout = 10)
    public void complete(UUID videoId) {
        jdbc.update("UPDATE video_deletions SET status = 'DONE', completed_at = clock_timestamp() WHERE video_id = ? AND status = 'PENDING'", videoId);
    }

    /** Backs off 30 s per failed attempt, capped at 10 minutes. */
    @Transactional(timeout = 10)
    public void retryLater(UUID videoId) {
        jdbc.update("""
            UPDATE video_deletions SET available_at = clock_timestamp() + least(attempts * interval '30 seconds', interval '10 minutes')
            WHERE video_id = ? AND status = 'PENDING'
            """, videoId);
    }
}
