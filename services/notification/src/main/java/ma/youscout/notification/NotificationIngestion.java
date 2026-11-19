package ma.youscout.notification;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applique un événement Kafka dans une transaction : inbox (déduplication par eventId), projection des relations,
 * puis notifications. Un événement invalide est mis en quarantaine et acquitté.
 *
 * Règles : personne n'est notifié de sa propre action, ni par ou pour un compte inactif, ni à travers un blocage
 * dans un sens ou dans l'autre. Un événement plus ancien que notification.max-event-age (24 h) met à jour les
 * projections sans notifier : ni les suivis rejoués par le rattrapage initial de User, ni l'historique des sujets
 * relu au premier démarrage ne produisent de notifications périmées.
 */
@Component
class NotificationIngestion {
    static final class InvalidEvent extends RuntimeException {
        InvalidEvent(String reason) { super(reason); }
    }
    private static final int MAX_EVENT_BYTES = 16384;
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Duration maxAge;

    NotificationIngestion(JdbcTemplate jdbc, PlatformTransactionManager manager,
                          @Value("${notification.max-event-age:PT24H}") Duration maxAge) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.maxAge = maxAge;
    }

    void handle(ConsumerRecord<String, String> record) {
        try {
            transactions.executeWithoutResult(status -> apply(record.topic(), record.value()));
        } catch (InvalidEvent invalid) {
            jdbc.update("""
                INSERT INTO notification_rejected_messages(topic, partition_id, record_offset, reason) VALUES (?, ?, ?, ?)
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
        if (root == null || !root.keySet().equals(ENVELOPE) || !(root.get("data") instanceof Map<?, ?> data))
            throw new InvalidEvent("INVALID_ENVELOPE");
        UUID eventId = uuid(root.get("eventId"));
        String type = String.valueOf(root.get("eventType")), producer = String.valueOf(root.get("producer"));
        Instant at = instant(root.get("occurredAt"));
        if (jdbc.update("INSERT INTO notification_inbox(event_id, topic) VALUES (?, ?) ON CONFLICT DO NOTHING", eventId, topic) == 0)
            return;
        switch (producer + "/" + type) {
            case "user-service/UserRelationChanged" -> relation(data, at);
            case "user-service/UserAccountChanged" -> account(data);
            // Le marqueur de fin de rattrapage est inutile : les suivis rejoués sont trop anciens pour notifier.
            case "user-service/UserRelationshipsSnapshotComplete" -> { }
            case "video-service/VideoPublished" -> published(data, at);
            case "video-service/VideoDeleted" -> jdbc.update("DELETE FROM notifications WHERE video_id = ?", uuid(data.get("videoId")));
            case "social-service/VideoLiked" -> {
                UUID video = uuid(data.get("videoId"));
                notify(subject(data.get("videoOwnerSubject")), "VIDEO_LIKED", subject(data.get("actorSubject")), video, null, null, at);
            }
            case "social-service/CommentAdded" -> commented(data, at);
            case "chat-service/MessageAccepted" -> message(data, at);
            default -> throw new InvalidEvent("UNKNOWN_TYPE");
        }
    }

    private void relation(Map<?, ?> data, Instant at) {
        String action = String.valueOf(data.get("action"));
        String actor = subject(data.get("actorSubject")), target = subject(data.get("targetSubject"));
        switch (action) {
            case "FOLLOW" -> {
                jdbc.update("INSERT INTO relation_follows VALUES (?, ?) ON CONFLICT DO NOTHING", actor, target);
                notify(target, "FOLLOWED", actor, null, null, null, at);
            }
            case "UNFOLLOW" -> {
                jdbc.update("DELETE FROM relation_follows WHERE follower_subject = ? AND followed_subject = ?", actor, target);
                jdbc.update("DELETE FROM notifications WHERE type = 'FOLLOWED' AND read_at IS NULL AND recipient_subject = ? AND actor_subject = ?",
                    target, actor);
            }
            case "BLOCK" -> {
                jdbc.update("INSERT INTO relation_blocks VALUES (?, ?) ON CONFLICT DO NOTHING", actor, target);
                // User retire aussi les suivis dans les deux sens ; ses notifications entre les deux comptes disparaissent.
                jdbc.update("DELETE FROM relation_follows WHERE (follower_subject, followed_subject) IN ((?, ?), (?, ?))", actor, target, target, actor);
                jdbc.update("DELETE FROM notifications WHERE (recipient_subject, actor_subject) IN ((?, ?), (?, ?))", actor, target, target, actor);
            }
            case "UNBLOCK" -> jdbc.update("DELETE FROM relation_blocks WHERE blocker_subject = ? AND blocked_subject = ?", actor, target);
            default -> throw new InvalidEvent("UNKNOWN_ACTION");
        }
    }

    private void account(Map<?, ?> data) {
        String subject = subject(data.get("subject"));
        switch (String.valueOf(data.get("status"))) {
            case "ACTIVE" -> jdbc.update("DELETE FROM inactive_accounts WHERE subject = ?", subject);
            case "DEACTIVATED", "CLOSING", "CLOSED" -> jdbc.update("INSERT INTO inactive_accounts VALUES (?) ON CONFLICT DO NOTHING", subject);
            default -> throw new InvalidEvent("UNKNOWN_STATUS");
        }
    }

    /** Chaque abonné actif et non bloqué du propriétaire reçoit la publication. */
    private void published(Map<?, ?> data, Instant at) {
        String owner = subject(data.get("ownerSubject"));
        UUID video = uuid(data.get("videoId"));
        if (!fresh(at) || inactive(owner)) return;
        jdbc.update("""
            INSERT INTO notifications(id, recipient_subject, type, actor_subject, video_id, created_at, updated_at)
            SELECT gen_random_uuid(), f.follower_subject, 'VIDEO_PUBLISHED', ?, ?, ?, ?
              FROM relation_follows f
             WHERE f.followed_subject = ? AND f.follower_subject <> ?
               AND NOT EXISTS (SELECT 1 FROM inactive_accounts i WHERE i.subject = f.follower_subject)
               AND NOT EXISTS (SELECT 1 FROM relation_blocks b
                                WHERE (b.blocker_subject, b.blocked_subject) IN ((f.follower_subject, ?), (?, f.follower_subject)))
            ON CONFLICT DO NOTHING
            """, owner, video, Timestamp.from(at), Timestamp.from(at), owner, owner, owner, owner);
    }

    /** Le propriétaire est notifié d'un commentaire ; l'auteur du commentaire parent, d'une réponse. */
    private void commented(Map<?, ?> data, Instant at) {
        String owner = subject(data.get("videoOwnerSubject")), author = subject(data.get("authorSubject"));
        UUID video = uuid(data.get("videoId")), comment = uuid(data.get("commentId"));
        String parentAuthor = data.get("parentAuthorSubject") == null ? null : subject(data.get("parentAuthorSubject"));
        if (parentAuthor != null) notify(parentAuthor, "REPLIED", author, video, comment, null, at);
        if (!owner.equals(parentAuthor)) notify(owner, "COMMENTED", author, video, comment, null, at);
    }

    /** Tant que la notification n'est pas lue, les messages suivants de la conversation l'actualisent. */
    private void message(Map<?, ?> data, Instant at) {
        String sender = subject(data.get("senderSubject")), recipient = subject(data.get("recipientSubject"));
        UUID conversation = uuid(data.get("conversationId"));
        if (!fresh(at) || !allowed(recipient, sender)) return;
        jdbc.update("""
            INSERT INTO notifications(id, recipient_subject, type, actor_subject, conversation_id, created_at, updated_at)
            VALUES (?, ?, 'MESSAGE', ?, ?, ?, ?)
            ON CONFLICT (recipient_subject, conversation_id) WHERE type = 'MESSAGE' AND read_at IS NULL
            DO UPDATE SET occurrences = notifications.occurrences + 1, updated_at = EXCLUDED.updated_at,
                          actor_subject = EXCLUDED.actor_subject, push_status = 'PENDING'
            """, UUID.randomUUID(), recipient, sender, conversation, Timestamp.from(at), Timestamp.from(at));
    }

    private void notify(String recipient, String type, String actor, UUID video, UUID comment, UUID conversation, Instant at) {
        if (!fresh(at) || !allowed(recipient, actor)) return;
        jdbc.update("""
            INSERT INTO notifications(id, recipient_subject, type, actor_subject, video_id, comment_id, conversation_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """, UUID.randomUUID(), recipient, type, actor, video, comment, conversation, Timestamp.from(at), Timestamp.from(at));
    }

    private boolean fresh(Instant at) {
        return at.isAfter(Instant.now().minus(maxAge));
    }

    private boolean allowed(String recipient, String actor) {
        if (recipient.equals(actor) || inactive(recipient) || inactive(actor)) return false;
        return !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM relation_blocks WHERE (blocker_subject, blocked_subject) IN ((?, ?), (?, ?)))
            """, Boolean.class, recipient, actor, actor, recipient));
    }

    private boolean inactive(String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM inactive_accounts WHERE subject = ?)", Boolean.class, subject));
    }

    static String subject(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 255 || text.indexOf('\0') >= 0)
            throw new InvalidEvent("INVALID_SUBJECT");
        return text;
    }

    private static UUID uuid(Object value) {
        try {
            return UUID.fromString((String) value);
        } catch (RuntimeException invalid) {
            throw new InvalidEvent("INVALID_ID");
        }
    }

    private static Instant instant(Object value) {
        try {
            return Instant.parse((String) value);
        } catch (RuntimeException invalid) {
            throw new InvalidEvent("INVALID_TIME");
        }
    }
}
