package ma.youscout.user;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Persists relationship events in the same database transaction as each mutation. */
@Component
class RelationEventOutbox {
    record RelationData(String action, String actorSubject, String targetSubject) {}
    record Envelope(String eventId, String eventType, int eventVersion, String occurredAt,
                    String producer, RelationData data) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    RelationEventOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void append(String action, String actor, String target) {
        UUID id = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Long sequence = jdbc.queryForObject(
                "SELECT next_value FROM user_event_sequence WHERE singleton = TRUE FOR UPDATE", Long.class);
        jdbc.update("UPDATE user_event_sequence SET next_value = next_value + 1 WHERE singleton = TRUE");
        var envelope = new Envelope(id.toString(), "UserRelationChanged", 1, occurredAt.toString(),
                "user-service", new RelationData(action, actor, target));
        jdbc.update("""
            INSERT INTO user_event_outbox(event_id, event_sequence, message_key, event_type, payload)
            VALUES (?, ?, 'youscout-user-relationships-v1', 'UserRelationChanged', ?::jsonb)
            """, id, sequence, json.writeValueAsString(envelope));
    }
}
