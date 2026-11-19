package ma.youscout.notification;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Boîte de notifications du compte connecté et enregistrement de ses appareils ; le sujet vient du jeton. */
@RestController
@RequestMapping("/v1/notifications")
class NotificationController {
    record Notification(UUID id, String type, String actorSubject, UUID videoId, UUID commentId, UUID conversationId,
                        int occurrences, Instant createdAt, Instant updatedAt, boolean read) {}
    record Unread(long count) {}

    private static final String COLUMNS = "id, type, actor_subject, video_id, comment_id, conversation_id, occurrences, created_at, updated_at, read_at";
    private static final RowMapper<Notification> ROW = (r, n) -> new Notification(r.getObject(1, UUID.class), r.getString(2),
        r.getString(3), r.getObject(4, UUID.class), r.getObject(5, UUID.class), r.getObject(6, UUID.class), r.getInt(7),
        r.getTimestamp(8).toInstant(), r.getTimestamp(9).toInstant(), r.getTimestamp(10) != null);
    private static final Set<String> PLATFORMS = Set.of("ANDROID", "IOS", "WEB");

    private final JdbcTemplate jdbc;
    private final DeviceTokens tokens;

    NotificationController(JdbcTemplate jdbc, DeviceTokens tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    /** Plus récentes d'abord ; {@code before} reprend après la dernière notification reçue. */
    @GetMapping
    ResponseEntity<List<Notification>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID before,
                                            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) throw bad("La taille de page doit être comprise entre 1 et 50.");
        String me = subject(jwt);
        if (before == null)
            return noStore(jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE recipient_subject = ?"
                + " ORDER BY updated_at DESC, id DESC LIMIT ?", ROW, me, limit));
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notifications WHERE id = ? AND recipient_subject = ?)",
                Boolean.class, before, me))) throw bad("Curseur invalide.");
        return noStore(jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE recipient_subject = ?"
            + " AND (updated_at, id) < (SELECT updated_at, id FROM notifications WHERE id = ?)"
            + " ORDER BY updated_at DESC, id DESC LIMIT ?", ROW, me, before, limit));
    }

    @GetMapping("/unread-count")
    ResponseEntity<Unread> unread(@AuthenticationPrincipal Jwt jwt) {
        return noStore(new Unread(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_subject = ? AND read_at IS NULL",
            Long.class, subject(jwt))));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        String me = subject(jwt);
        if (jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, clock_timestamp()) WHERE id = ? AND recipient_subject = ?",
                id, me) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable.");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    ResponseEntity<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        jdbc.update("UPDATE notifications SET read_at = clock_timestamp() WHERE recipient_subject = ? AND read_at IS NULL", subject(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Un jeton déjà connu change de propriétaire : l'appareil appartient au dernier compte connecté. */
    @PutMapping(path = "/devices", consumes = "application/json")
    ResponseEntity<Void> register(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        String me = subject(jwt), token = token(body, Set.of("platform", "token"));
        if (!(body.get("platform") instanceof String platform) || !PLATFORMS.contains(platform))
            throw bad("Plateforme attendue : ANDROID, IOS ou WEB.");
        jdbc.update("""
            INSERT INTO devices(id, subject, platform, token_hash, token_ciphertext) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (token_hash) DO UPDATE SET subject = EXCLUDED.subject, platform = EXCLUDED.platform,
                   token_ciphertext = EXCLUDED.token_ciphertext, last_seen_at = clock_timestamp()
            """, UUID.randomUUID(), me, platform, tokens.hash(token), tokens.encrypt(token));
        return ResponseEntity.noContent().build();
    }

    /** À la déconnexion : l'appareil ne reçoit plus les notifications de ce compte. */
    @PostMapping(path = "/devices/unregister", consumes = "application/json")
    ResponseEntity<Void> unregister(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        String token = token(body, Set.of("token"));
        jdbc.update("DELETE FROM devices WHERE token_hash = ? AND subject = ?", tokens.hash(token), subject(jwt));
        return ResponseEntity.noContent().build();
    }

    private static String token(Map<String, Object> body, Set<String> allowed) {
        if (body == null || !allowed.containsAll(body.keySet())) throw bad("Champ inconnu.");
        if (!(body.get("token") instanceof String token) || token.isBlank() || token.length() > 4096 || token.indexOf('\0') >= 0)
            throw bad("Jeton d'appareil invalide.");
        return token;
    }

    private static String subject(Jwt jwt) {
        try {
            return NotificationIngestion.subject(jwt.getSubject());
        } catch (NotificationIngestion.InvalidEvent invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité du jeton invalide.");
        }
    }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
