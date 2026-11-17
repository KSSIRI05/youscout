package ma.youscout.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RelationOutboxRepository {
    record Pending(UUID id, String key, String type, String payload) {}
    private final JdbcTemplate jdbc;

    RelationOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Pending> lockNext() {
        List<Pending> rows = jdbc.query("""
            SELECT event_id, message_key, event_type, payload::text
              FROM user_event_outbox
             WHERE published_at IS NULL
             ORDER BY event_sequence
             LIMIT 1 FOR UPDATE SKIP LOCKED
            """, (row, index) -> new Pending(row.getObject(1, UUID.class),
                row.getString(2), row.getString(3), row.getString(4)));
        return rows.stream().findFirst();
    }

    void published(UUID id) {
        jdbc.update("UPDATE user_event_outbox SET published_at = clock_timestamp() WHERE event_id = ?", id);
    }

    void retry(UUID id) {
        jdbc.update("UPDATE user_event_outbox SET attempts = attempts + 1 WHERE event_id = ?", id);
    }
}
