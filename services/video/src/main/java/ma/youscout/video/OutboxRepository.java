package ma.youscout.video;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** L'événement immuable capture la référence du fichier confirmé, sans jeton ni URL signée. */
@Repository
class OutboxRepository {
    record Event(UUID id, UUID aggregateId, String payload, int attempts) {}
    private final JdbcTemplate jdbc;
    OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID videoId) {
        int inserted = jdbc.update("""
            INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
            SELECT event_id, id, 'VideoUploaded', jsonb_build_object(
                'eventId', event_id, 'eventType', 'VideoUploaded', 'eventVersion', 1,
                'occurredAt', completed_at, 'producer', 'video-service',
                'data', jsonb_build_object('videoId', id, 's3Bucket', s3_bucket, 's3Key', s3_key,
                    'sourceETag', source_etag, 'contentType', content_type, 'sizeBytes', size_bytes))
            FROM (SELECT ?::uuid AS event_id, v.* FROM videos v WHERE id = ? AND status = 'UPLOADED') confirmed
            """, UUID.randomUUID(), videoId);
        if (inserted != 1) throw new IllegalStateException("Confirmed video required for outbox.");
    }

    // Le verrou reste détenu jusqu'à l'ACK et au commit ; une autre instance saute la ligne.
    Optional<Event> lockNext() {
        return jdbc.query("""
            SELECT id, aggregate_id, payload::text, attempts FROM outbox_events
            WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp()
            ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, (row, n) -> new Event(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getString(3), row.getInt(4))).stream().findFirst();
    }

    void published(UUID id) {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp(), last_error = NULL WHERE id = ?", id);
    }

    void retry(Event event) {
        long delaySeconds = Math.min(300, 1L << Math.min(9, event.attempts()));
        jdbc.update("""
            UPDATE outbox_events SET attempts = LEAST(attempts::bigint + 1, 2147483647)::integer,
                next_attempt_at = clock_timestamp() + (? * interval '1 second'), last_error = 'KAFKA_UNAVAILABLE'
            WHERE id = ?
            """, delaySeconds, event.id());
    }
}
