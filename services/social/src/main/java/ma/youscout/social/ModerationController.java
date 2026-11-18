package ma.youscout.social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signalement des commentaires (U17) et file de modération (A03/A04).
 * Les routes /admin exigent le rôle admin, dans la Gateway comme ici ; chaque décision est journalisée.
 */
@RestController
@RequestMapping("/v1/social")
class ModerationController {
    record Report(String reporterSubject, String reason, Instant createdAt) {}
    record Target(String type, UUID targetId, UUID videoId, String commentBody, String commentAuthor, boolean commentRemoved,
                  int reportCount, Instant firstReportedAt, Instant lastReportedAt, List<Report> reports) {}
    record Page(List<Target> items, boolean hasMore) {}
    record Decision(String targetType, UUID targetId, String decision, int reportsClosed) {}

    private static final int PAGE_SIZE = 20;
    private static final int REPORTS_SHOWN = 5;
    private static final Set<String> STATUSES = Set.of("OPEN", "REVIEWED", "DISMISSED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ModerationController(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** U17 : un compte peut signaler un commentaire visible une seule fois ; un nouveau signalement est ignoré. */
    @PostMapping("/comments/{commentId}/reports")
    ResponseEntity<Void> reportComment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
                                       @RequestBody Map<String, Object> body) {
        String reporter = subject(jwt);
        String reason = text(body.get("reason"), 500, "Indique un motif (500 caractères maximum).");
        if (!exists("SELECT EXISTS(SELECT 1 FROM video_comments WHERE id = ? AND deleted_at IS NULL)", commentId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire introuvable.");
        jdbc.update("INSERT INTO comment_reports(id, comment_id, reporter_subject, reason) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (comment_id, reporter_subject) DO NOTHING", UUID.randomUUID(), commentId, reporter, reason);
        return ResponseEntity.accepted().build();
    }

    /** A03 : cibles signalées, la plus récemment signalée d'abord, avec le nombre et les derniers motifs. */
    @GetMapping("/admin/reports")
    ResponseEntity<Page> reports(@RequestParam(defaultValue = "VIDEO") String type, @RequestParam(defaultValue = "OPEN") String status,
                                 @RequestParam(defaultValue = "0") int offset) {
        boolean video = targetType(type).equals("VIDEO");
        if (!STATUSES.contains(status)) throw bad("Statut inconnu : OPEN, REVIEWED ou DISMISSED.");
        if (offset < 0 || offset > 10000) throw bad("offset doit être compris entre 0 et 10000.");
        String table = video ? "video_reports" : "comment_reports", column = video ? "video_id" : "comment_id";
        record Group(UUID id, int count, Instant first, Instant last) {}
        var groups = jdbc.query("SELECT " + column + ", count(*), min(created_at), max(created_at) FROM " + table
                + " WHERE status = ? GROUP BY " + column + " ORDER BY max(created_at) DESC, " + column + " LIMIT ? OFFSET ?",
            (r, n) -> new Group(r.getObject(1, UUID.class), r.getInt(2), r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant()),
            status, PAGE_SIZE + 1, offset);
        var shown = groups.subList(0, Math.min(PAGE_SIZE, groups.size()));
        UUID[] ids = shown.stream().map(Group::id).toArray(UUID[]::new);

        var reasons = new LinkedHashMap<UUID, List<Report>>();
        jdbc.query("SELECT " + column + ", reporter_subject, reason, created_at FROM " + table
                + " WHERE " + column + " = ANY (?) AND status = ? ORDER BY created_at DESC", rs -> {
            var list = reasons.computeIfAbsent(rs.getObject(1, UUID.class), id -> new ArrayList<>());
            if (list.size() < REPORTS_SHOWN) list.add(new Report(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
        }, ids, status);
        record Comment(UUID videoId, String body, String author, boolean removed) {}
        var comments = new LinkedHashMap<UUID, Comment>();
        if (!video) jdbc.query("SELECT id, video_id, body, author_subject, deleted_at IS NOT NULL FROM video_comments WHERE id = ANY (?)",
            rs -> { comments.put(rs.getObject(1, UUID.class), new Comment(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getBoolean(5))); }, (Object) ids);

        var items = shown.stream().map(group -> {
            Comment comment = comments.get(group.id());
            return new Target(video ? "VIDEO" : "COMMENT", group.id(), video ? group.id() : comment.videoId(),
                video ? null : comment.body(), video ? null : comment.author(), !video && comment.removed(),
                group.count(), group.first(), group.last(), reasons.getOrDefault(group.id(), List.of()));
        }).toList();
        return noStore(new Page(items, groups.size() > PAGE_SIZE));
    }

    /** A04 : classe sans suite (DISMISSED) ou constate une action (ACTION_TAKEN) pour tous les signalements ouverts d'une cible. */
    @PostMapping("/admin/reports/{type}/{targetId}/decision")
    ResponseEntity<Decision> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String type, @PathVariable UUID targetId,
                                    @RequestBody Map<String, Object> body) {
        String admin = subject(jwt), target = targetType(type);
        if (!(body.get("decision") instanceof String decision) || !Set.of("DISMISSED", "ACTION_TAKEN").contains(decision))
            throw bad("Décision attendue : DISMISSED ou ACTION_TAKEN.");
        String note = optionalText(body.get("note"));
        Decision result = transactions.execute(tx -> {
            int closed = close(target, targetId, decision.equals("DISMISSED") ? "DISMISSED" : "REVIEWED", admin);
            if (closed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun signalement ouvert pour cette cible.");
            audit(admin, target, targetId, decision, note, closed);
            return new Decision(target, targetId, decision, closed);
        });
        return noStore(result);
    }

    /** A04 : retire un commentaire de toutes les lectures et ferme ses signalements ouverts. Un nouvel appel ne change rien. */
    @PostMapping("/admin/comments/{commentId}/remove")
    ResponseEntity<Decision> removeComment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String admin = subject(jwt);
        String note = optionalText(body == null ? null : body.get("note"));
        Decision result = transactions.execute(tx -> {
            List<Boolean> visible = jdbc.queryForList("SELECT deleted_at IS NULL FROM video_comments WHERE id = ? FOR UPDATE", Boolean.class, commentId);
            if (visible.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Commentaire introuvable.");
            if (visible.getFirst())
                jdbc.update("UPDATE video_comments SET deleted_at = clock_timestamp(), removed_by = ? WHERE id = ?", admin, commentId);
            int closed = close("COMMENT", commentId, "REVIEWED", admin);
            if (visible.getFirst() || closed > 0) audit(admin, "COMMENT", commentId, "COMMENT_REMOVED", note, closed);
            return new Decision("COMMENT", commentId, "COMMENT_REMOVED", closed);
        });
        return noStore(result);
    }

    private int close(String target, UUID targetId, String status, String admin) {
        String table = target.equals("VIDEO") ? "video_reports" : "comment_reports", column = target.equals("VIDEO") ? "video_id" : "comment_id";
        return jdbc.update("UPDATE " + table + " SET status = ?, decided_at = clock_timestamp(), decided_by = ? WHERE " + column
            + " = ? AND status = 'OPEN'", status, admin, targetId);
    }

    private void audit(String admin, String target, UUID targetId, String decision, String note, int closed) {
        jdbc.update("INSERT INTO moderation_decisions(id, admin_subject, target_type, target_id, decision, note, reports_closed) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), admin, target, targetId, decision, note, closed);
    }

    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
    private static String targetType(String type) {
        if (!Set.of("VIDEO", "COMMENT").contains(type)) throw bad("Type de cible attendu : VIDEO ou COMMENT.");
        return type;
    }
    private static String text(Object value, int max, String message) {
        String text = value instanceof String raw ? raw.strip() : "";
        if (text.isBlank() || text.length() > max || text.indexOf('\0') >= 0) throw bad(message);
        return text;
    }
    private static String optionalText(Object value) {
        if (value == null) return null;
        String text = value instanceof String raw ? raw.strip() : null;
        if (text == null || text.length() > 500 || text.indexOf('\0') >= 0) throw bad("La note doit contenir au plus 500 caractères.");
        return text.isEmpty() ? null : text;
    }
    private static String subject(Jwt jwt) {
        String s = jwt.getSubject();
        if (s == null || s.isBlank() || s.length() > 255 || s.indexOf('\0') >= 0) throw bad("Identifiant utilisateur invalide.");
        return s;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
