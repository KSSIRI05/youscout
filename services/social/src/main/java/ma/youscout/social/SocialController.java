package ma.youscout.social;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** API persistante des likes, commentaires, signalements et messages directs. */
@RestController
@RequestMapping("/v1/social")
class SocialController {
    record LikeState(long count, boolean liked) {}
    record Comment(UUID id, UUID videoId, String authorSubject, String body, Instant createdAt) {}
    record Message(UUID id, String senderSubject, String recipientSubject, String body, Instant createdAt) {}
    private final JdbcTemplate jdbc;
    SocialController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        jdbc.update("INSERT INTO video_likes(video_id, user_subject) VALUES (?, ?) ON CONFLICT DO NOTHING", videoId, subject);
        return likes(jwt, videoId);
    }

    @DeleteMapping("/videos/{videoId}/likes")
    ResponseEntity<LikeState> unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        jdbc.update("DELETE FROM video_likes WHERE video_id = ? AND user_subject = ?", videoId, subject(jwt));
        return likes(jwt, videoId);
    }

    @GetMapping("/videos/{videoId}/comments")
    ResponseEntity<List<Comment>> comments(@PathVariable UUID videoId) {
        var list = jdbc.query("SELECT id, video_id, author_subject, body, created_at FROM video_comments WHERE video_id = ? AND deleted_at IS NULL ORDER BY created_at LIMIT 100",
            (r, n) -> new Comment(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()), videoId);
        return noStore(list);
    }

    @PostMapping("/videos/{videoId}/comments")
    ResponseEntity<Comment> comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @RequestBody Map<String, Object> body) {
        String text = body.get("body") instanceof String value ? value.strip() : "";
        if (text.isBlank() || text.length() > 1000 || text.indexOf('\0') >= 0) throw bad("Le commentaire doit contenir de 1 à 1000 caractères.");
        UUID id = UUID.randomUUID();
        return noStore(jdbc.queryForObject("INSERT INTO video_comments(id, video_id, author_subject, body) VALUES (?, ?, ?, ?) RETURNING id, video_id, author_subject, body, created_at",
            (r, n) -> new Comment(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()), id, videoId, subject(jwt), text));
    }

    @DeleteMapping("/comments/{commentId}")
    ResponseEntity<Void> deleteComment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        int changed = jdbc.update("UPDATE video_comments SET deleted_at = clock_timestamp() WHERE id = ? AND author_subject = ? AND deleted_at IS NULL", commentId, subject(jwt));
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire introuvable.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/videos/{videoId}/reports")
    ResponseEntity<Void> report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") instanceof String value ? value.strip() : "";
        if (reason.isBlank() || reason.length() > 500 || reason.indexOf('\0') >= 0) throw bad("Indique un motif (500 caractères maximum).");
        jdbc.update("INSERT INTO video_reports(id, video_id, reporter_subject, reason) VALUES (?, ?, ?, ?) ON CONFLICT (video_id, reporter_subject) DO NOTHING",
            UUID.randomUUID(), videoId, subject(jwt), reason);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/messages")
    ResponseEntity<List<Message>> messages(@AuthenticationPrincipal Jwt jwt, @RequestParam String with) {
        String me = subject(jwt); validateSubject(with);
        return noStore(jdbc.query("SELECT id, sender_subject, recipient_subject, body, created_at FROM direct_messages WHERE (sender_subject = ? AND recipient_subject = ?) OR (sender_subject = ? AND recipient_subject = ?) ORDER BY created_at LIMIT 500",
            (r, n) -> new Message(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()), me, with, with, me));
    }

    @PostMapping("/messages")
    ResponseEntity<Message> sendMessage(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        String sender = subject(jwt);
        String recipient = body.get("recipientSubject") instanceof String value ? value : "";
        String text = body.get("body") instanceof String value ? value.strip() : "";
        validateSubject(recipient);
        if (sender.equals(recipient)) throw bad("Tu ne peux pas t'envoyer un message.");
        if (text.isBlank() || text.length() > 2000 || text.indexOf('\0') >= 0) throw bad("Le message doit contenir de 1 à 2000 caractères.");
        UUID id = UUID.randomUUID();
        return noStore(jdbc.queryForObject("INSERT INTO direct_messages(id, sender_subject, recipient_subject, body) VALUES (?, ?, ?, ?) RETURNING id, sender_subject, recipient_subject, body, created_at",
            (r, n) -> new Message(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()), id, sender, recipient, text));
    }

    private static String subject(Jwt jwt) { String s = jwt.getSubject(); validateSubject(s); return s; }
    private static void validateSubject(String s) { if (s == null || s.isBlank() || s.length() > 255 || s.indexOf('\0') >= 0) throw bad("Identifiant utilisateur invalide."); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
