package ma.youscout.social;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Écrit VideoLiked et CommentAdded (v1) dans l'outbox, dans la transaction de l'interaction elle-même.
 * La clé Kafka est la vidéo : les événements d'une même vidéo restent ordonnés. Aucun texte de commentaire n'est publié.
 */
@Component
class InteractionEvents {
    record Liked(String videoId, String videoOwnerSubject, String actorSubject, String likedAt) {}
    record Commented(String commentId, String videoId, String videoOwnerSubject, String authorSubject,
                     String parentId, String parentAuthorSubject, String createdAt) {}
    record Envelope(String eventId, String eventType, int eventVersion, String occurredAt, String producer, Object data) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    InteractionEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void liked(UUID videoId, String owner, String actor) {
        Instant now = Instant.now();
        insert(videoId, "VideoLiked", now, new Liked(videoId.toString(), owner, actor, now.toString()));
    }

    void commented(SocialController.Comment comment, String owner, String parentAuthor) {
        insert(comment.videoId(), "CommentAdded", comment.createdAt(), new Commented(comment.id().toString(),
            comment.videoId().toString(), owner, comment.authorSubject(),
            comment.parentId() == null ? null : comment.parentId().toString(), parentAuthor, comment.createdAt().toString()));
    }

    private void insert(UUID videoId, String type, Instant occurredAt, Object data) {
        UUID id = UUID.randomUUID();
        var envelope = new Envelope(id.toString(), type, 1, occurredAt.toString(), "social-service", data);
        jdbc.update("INSERT INTO social_event_outbox(event_id, message_key, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
            id, videoId.toString(), type, json.writeValueAsString(envelope));
    }
}
