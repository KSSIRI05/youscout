package ma.youscout.mediaworker;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** L'unicité PostgreSQL protège aussi contre plusieurs instances ou un crash avant l'ACK Kafka. */
@Repository
class JobStore {
    private final JdbcTemplate jdbc;
    JobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(timeout = 10)
    public void accept(VideoUploaded event, String topic, int partition, long offset) {
        int created = jdbc.update("""
            INSERT INTO processing_jobs(event_id, video_id, fingerprint, s3_bucket, s3_key, source_etag,
                content_type, size_bytes, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """, event.eventId(), event.videoId(), event.fingerprint(), event.bucket(), event.key(), event.etag(),
                event.contentType(), event.sizeBytes(), Timestamp.from(event.occurredAt()));
        if (created == 1) return;
        var existing = jdbc.queryForList("SELECT fingerprint FROM processing_jobs WHERE event_id = ?", String.class, event.eventId());
        if (!existing.isEmpty() && existing.getFirst().equals(event.fingerprint())) return;
        // Un identifiant réutilisé avec d'autres données n'écrase jamais la source déjà enregistrée.
        reject(topic, partition, offset, existing.isEmpty() ? "VIDEO_ALREADY_QUEUED" : "EVENT_ID_CONFLICT");
    }

    @Transactional(timeout = 10)
    public void reject(String topic, int partition, long offset, String reason) {
        jdbc.update("""
            INSERT INTO rejected_messages(topic, partition_id, record_offset, reason) VALUES (?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, topic, partition, offset, reason);
    }
}
