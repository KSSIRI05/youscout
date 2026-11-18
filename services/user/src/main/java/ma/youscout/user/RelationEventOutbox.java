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
    record AccountData(String subject, String status) {}
    record Envelope(String eventId, String eventType, int eventVersion, String occurredAt,
                    String producer, Object data) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    RelationEventOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void append(String action, String actor, String target) {
        insert("UserRelationChanged", new RelationData(action, actor, target));
    }

    /** UserAccountChanged v1 : même topic et même clé que les relations, donc ordonné avec elles. */
    void appendAccount(String subject, String status) {
        insert("UserAccountChanged", new AccountData(subject, status));
    }

    private void insert(String type, Object data) {
        UUID id = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Long sequence = jdbc.queryForObject(
                "SELECT next_value FROM user_event_sequence WHERE singleton = TRUE FOR UPDATE", Long.class);
        jdbc.update("UPDATE user_event_sequence SET next_value = next_value + 1 WHERE singleton = TRUE");
        var envelope = new Envelope(id.toString(), type, 1, occurredAt.toString(), "user-service", data);
        jdbc.update("""
            INSERT INTO user_event_outbox(event_id, event_sequence, message_key, event_type, payload)
            VALUES (?, ?, 'youscout-user-relationships-v1', ?, ?::jsonb)
            """, id, sequence, type, json.writeValueAsString(envelope));
    }
}
