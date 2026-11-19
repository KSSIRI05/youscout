package ma.youscout.analytics;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compte un événement dans le jour UTC où il s'est produit, avec déduplication par eventId dans la même transaction.
 * Les autres événements des sujets lus (suivis, blocages, statuts de compte) sont acquittés sans effet.
 */
@Component
class AnalyticsIngestion {
    static final class InvalidEvent extends RuntimeException {
        InvalidEvent(String reason) { super(reason); }
    }
    private static final int MAX_EVENT_BYTES = 65536;
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data");
    private static final Map<String, String> METRICS = Map.of(
        "user-service/UserProfileCreated", "NEW_ACCOUNTS",
        "video-service/VideoPublished", "VIDEOS_PUBLISHED",
        "social-service/VideoLiked", "LIKES",
        "social-service/CommentAdded", "COMMENTS",
        "chat-service/MessageAccepted", "MESSAGES");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    AnalyticsIngestion(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    void handle(ConsumerRecord<String, String> record) {
        try {
            transactions.executeWithoutResult(status -> apply(record.topic(), record.value()));
        } catch (InvalidEvent invalid) {
            jdbc.update("""
                INSERT INTO analytics_rejected_messages(topic, partition_id, record_offset, reason) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, record.topic(), record.partition(), record.offset(), invalid.getMessage());
        }
    }

    private void apply(String topic, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) throw new InvalidEvent("TOO_LARGE");
        Map<?, ?> root;
        try {
            root = JSON.readValue(raw, Map.class);
        } catch (RuntimeException malformed) {
            throw new InvalidEvent("MALFORMED");
        }
        if (root == null || !root.keySet().equals(ENVELOPE) || !(root.get("data") instanceof Map<?, ?>))
            throw new InvalidEvent("INVALID_ENVELOPE");
        String metric = METRICS.get(root.get("producer") + "/" + root.get("eventType"));
        if (metric == null) return;
        UUID eventId;
        Instant at;
        try {
            eventId = UUID.fromString((String) root.get("eventId"));
            at = Instant.parse((String) root.get("occurredAt"));
        } catch (RuntimeException invalid) {
            throw new InvalidEvent("INVALID_ID_OR_TIME");
        }
        if (jdbc.update("INSERT INTO analytics_inbox(event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING", eventId, topic) == 0) return;
        jdbc.update("""
            INSERT INTO daily_activity(day, metric, count) VALUES (?, ?, 1)
            ON CONFLICT (day, metric) DO UPDATE SET count = daily_activity.count + 1
            """, java.sql.Date.valueOf(at.atZone(ZoneOffset.UTC).toLocalDate()), metric);
    }
}
