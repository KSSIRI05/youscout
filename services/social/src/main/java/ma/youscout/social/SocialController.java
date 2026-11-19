package ma.youscout.social;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * API persistante des likes, commentaires et signalements ; les messages directs appartiennent à Chat.
 * Un nouveau like ou commentaire écrit aussi son événement (VideoLiked, CommentAdded) dans l'outbox, même transaction.
 * Les écritures vérifient auprès de Video que la vidéo est publiée et auprès de User qu'aucun blocage ne s'applique.
 */
@RestController
@RequestMapping("/v1/social")
class SocialController {
    record LikeState(long count, boolean liked) {}
    record Comment(UUID id, UUID videoId, UUID parentId, String authorSubject, String body, Instant createdAt) {}
    private record Parent(UUID videoId, UUID parentId, String authorSubject, boolean deleted) {}

    private static final String COMMENT_COLUMNS = "id, video_id, parent_id, author_subject, body, created_at";
    private static final RowMapper<Comment> COMMENT = (r, n) -> new Comment(r.getObject(1, UUID.class),
        r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getString(4), r.getString(5), r.getTimestamp(6).toInstant());
    private final JdbcTemplate jdbc;
    private final SocialReferences references;
    private final InteractionEvents events;
    private final TransactionTemplate transactions;

    SocialController(JdbcTemplate jdbc, SocialReferences references, InteractionEvents events, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.references = references;
        this.events = events;
        this.transactions = new TransactionTemplate(manager);
    }

    @GetMapping("/videos/{videoId}/likes")
    ResponseEntity<LikeState> likes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        String subject = subject(jwt);
        Long count = jdbc.queryForObject("SELECT count(*) FROM video_likes WHERE video_id = ?", Long.class, videoId);
        Boolean liked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM video_likes WHERE video_id = ? AND user_subject = ?)", Boolean.class, videoId, subject);
        return noStore(new LikeState(count, Boolean.TRUE.equals(liked)));
    }

    @PutMapping("/videos/{videoId}/likes")
    ResponseEntity<LikeState> like(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        String subject = subject(jwt);
        String owner = references.videoOwner(videoId, jwt.getTokenValue());
        requireNotBlocked(owner, jwt);
        // Le like et son événement sont validés ensemble ; un like répété n'émet rien.
        transactions.executeWithoutResult(status -> {
            if (jdbc.update("INSERT INTO video_likes(video_id, user_subject) VALUES (?, ?) ON CONFLICT DO NOTHING", videoId, subject) == 1)
                events.liked(videoId, owner, subject);
        });
        return likes(jwt, videoId);
    }

    // Retirer son like reste toujours possible, même après un blocage ou un retrait de la vidéo.
    @DeleteMapping("/videos/{videoId}/likes")
    ResponseEntity<LikeState> unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        jdbc.update("DELETE FROM video_likes WHERE video_id = ? AND user_subject = ?", videoId, subject(jwt));
        return likes(jwt, videoId);
    }

    /** Page la plus récente, en ordre chronologique ; {@code before} renvoie les commentaires plus anciens. */
    @GetMapping("/videos/{videoId}/comments")
    ResponseEntity<List<Comment>> comments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId,
                                           @RequestParam(required = false) UUID before,
                                           @RequestParam(defaultValue = "50") int limit) {
        subject(jwt);
        validateLimit(limit, 100);
        String[] hidden = references.blockedEitherWay(jwt.getTokenValue()).toArray(String[]::new);
        if (before == null) {
            return noStore(jdbc.query("SELECT " + COMMENT_COLUMNS + " FROM video_comments WHERE video_id = ? AND deleted_at IS NULL"
                + " AND NOT (author_subject = ANY (?)) ORDER BY created_at DESC, id DESC LIMIT ?", COMMENT, videoId, hidden, limit).reversed());
        }
        if (!exists("SELECT EXISTS(SELECT 1 FROM video_comments WHERE id = ? AND video_id = ?)", before, videoId)) throw bad("Curseur invalide.");
        return noStore(jdbc.query("SELECT " + COMMENT_COLUMNS + " FROM video_comments WHERE video_id = ? AND deleted_at IS NULL"
            + " AND NOT (author_subject = ANY (?))"
            + " AND (created_at, id) < (SELECT created_at, id FROM video_comments WHERE id = ?)"
            + " ORDER BY created_at DESC, id DESC LIMIT ?", COMMENT, videoId, hidden, before, limit).reversed());
    }

    @PostMapping("/videos/{videoId}/comments")
    ResponseEntity<Comment> comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @RequestBody Map<String, Object> body) {
        String author = subject(jwt);
        String text = body.get("body") instanceof String value ? value.strip() : "";
        if (text.isBlank() || text.length() > 1000 || text.indexOf('\0') >= 0) throw bad("Le commentaire doit contenir de 1 à 1000 caractères.");
        UUID parentId = parentId(body.get("parentId"));
        String owner = references.videoOwner(videoId, jwt.getTokenValue());
        Set<String> blocked = references.blockedEitherWay(jwt.getTokenValue());
        if (blocked.contains(owner)) throw blockedInteraction();
        String parentAuthor = null;
        if (parentId != null) {
            List<Parent> parents = jdbc.query("SELECT video_id, parent_id, author_subject, deleted_at IS NOT NULL FROM video_comments WHERE id = ?",
                (r, n) -> new Parent(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getBoolean(4)), parentId);
            if (parents.isEmpty() || parents.getFirst().deleted() || !parents.getFirst().videoId().equals(videoId))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire parent introuvable.");
            if (parents.getFirst().parentId() != null) throw bad("On ne peut répondre qu'à un commentaire principal.");
            if (blocked.contains(parents.getFirst().authorSubject())) throw blockedInteraction();
            parentAuthor = parents.getFirst().authorSubject();
        }
        String repliedTo = parentAuthor;
        return noStore(transactions.execute(status -> {
            Comment created = jdbc.queryForObject("INSERT INTO video_comments(id, video_id, parent_id, author_subject, body, video_owner_subject)"
                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COMMENT_COLUMNS, COMMENT, UUID.randomUUID(), videoId, parentId, author, text, owner);
            events.commented(created, owner, repliedTo);
            return created;
        }));
    }

    /** L'auteur ou le propriétaire de la vidéo (U13) peut retirer un commentaire. */
    @DeleteMapping("/comments/{commentId}")
    ResponseEntity<Void> deleteComment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        String subject = subject(jwt);
        int changed = jdbc.update("UPDATE video_comments SET deleted_at = clock_timestamp() WHERE id = ? AND deleted_at IS NULL"
            + " AND (author_subject = ? OR video_owner_subject = ?)", commentId, subject, subject);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire introuvable.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/videos/{videoId}/reports")
    ResponseEntity<Void> report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @RequestBody Map<String, Object> body) {
        String reporter = subject(jwt);
        String reason = body.get("reason") instanceof String value ? value.strip() : "";
        if (reason.isBlank() || reason.length() > 500 || reason.indexOf('\0') >= 0) throw bad("Indique un motif (500 caractères maximum).");
        // Un signalement reste possible malgré un blocage ; la vidéo doit seulement exister et être publiée.
        references.videoOwner(videoId, jwt.getTokenValue());
        jdbc.update("INSERT INTO video_reports(id, video_id, reporter_subject, reason) VALUES (?, ?, ?, ?) ON CONFLICT (video_id, reporter_subject) DO NOTHING",
            UUID.randomUUID(), videoId, reporter, reason);
        return ResponseEntity.accepted().build();
    }

    private void requireNotBlocked(String other, Jwt jwt) {
        if (references.blockedEitherWay(jwt.getTokenValue()).contains(other)) throw blockedInteraction();
    }
    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
    private static UUID parentId(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw bad("Commentaire parent invalide.");
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException invalid) { throw bad("Commentaire parent invalide."); }
    }
    private static void validateLimit(int limit, int max) { if (limit < 1 || limit > max) throw bad("La taille de page doit être comprise entre 1 et " + max + "."); }
    private static String subject(Jwt jwt) { String s = jwt.getSubject(); validateSubject(s); return s; }
    static void validateSubject(String s) { if (s == null || s.isBlank() || s.length() > 255 || s.indexOf('\0') >= 0) throw bad("Identifiant utilisateur invalide."); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException blockedInteraction() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Un blocage empêche cette interaction."); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
