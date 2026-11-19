package ma.youscout.social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** A02 et A06 : tous les commentaires avec leur auteur, et les totaux d'interactions ; routes /v1/social/admin réservées au rôle admin. */
@RestController
@RequestMapping("/v1/social/admin")
class AdminContentController {
    record AdminComment(UUID id, UUID videoId, UUID parentId, String authorSubject, String body, Instant createdAt,
                        boolean deleted, boolean removedByModeration) {}
    record InteractionTotals(long comments, long likes, long openReports) {}

    private static final RowMapper<AdminComment> ROW = (r, n) -> new AdminComment(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
        r.getObject(3, UUID.class), r.getString(4), r.getString(5), r.getTimestamp(6).toInstant(), r.getBoolean(7), r.getBoolean(8));

    private final JdbcTemplate jdbc;

    AdminContentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Plus récents d'abord, y compris supprimés ou retirés (signalés comme tels). Filtres facultatifs : vidéo, auteur ;
     * {@code before} reprend après le dernier commentaire reçu.
     */
    @GetMapping("/comments")
    ResponseEntity<List<AdminComment>> comments(@RequestParam(required = false) UUID videoId, @RequestParam(required = false) String author,
                                                @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La taille de page doit être comprise entre 1 et 100.");
        var sql = new StringBuilder("""
            SELECT id, video_id, parent_id, author_subject, body, created_at, deleted_at IS NOT NULL, removed_by IS NOT NULL
              FROM video_comments WHERE TRUE""");
        var args = new ArrayList<Object>();
        if (videoId != null) { sql.append(" AND video_id = ?"); args.add(videoId); }
        if (author != null) { SocialController.validateSubject(author); sql.append(" AND author_subject = ?"); args.add(author); }
        if (before != null) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM video_comments WHERE id = ?)", Boolean.class, before)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Curseur invalide.");
            sql.append(" AND (created_at, id) < (SELECT created_at, id FROM video_comments WHERE id = ?)");
            args.add(before);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jdbc.query(sql.toString(), ROW, args.toArray()));
    }

    /** Commentaires visibles, likes et signalements encore ouverts (vidéos et commentaires). */
    @GetMapping("/stats")
    ResponseEntity<InteractionTotals> stats() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM video_comments WHERE deleted_at IS NULL),
                   (SELECT count(*) FROM video_likes),
                   (SELECT count(*) FROM video_reports WHERE status = 'OPEN') + (SELECT count(*) FROM comment_reports WHERE status = 'OPEN')
            """, (r, n) -> new InteractionTotals(r.getLong(1), r.getLong(2), r.getLong(3))));
    }
}
