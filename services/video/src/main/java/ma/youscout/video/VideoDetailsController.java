package ma.youscout.video;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

/** Consultation privée du traitement ; les clés S3 et URL ne sont pas exposées. */
@RestController
@RequestMapping("/v1/videos")
class VideoDetailsController {
    record Details(UUID id, String title, String uploadStatus, String processingStatus, String errorCode,
                   Long durationMillis, Integer width, Integer height, Instant processingUpdatedAt) {}
    private final JdbcTemplate jdbc;
    VideoDetailsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/{id}")
    ResponseEntity<Details> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var rows = jdbc.query("SELECT * FROM videos WHERE id = ? AND owner_subject = ?", (r, n) -> {
            var date = r.getTimestamp("processing_updated_at");
            return new Details(id, r.getString("title"), r.getString("status"), r.getString("processing_status"), r.getString("processing_error"),
                (Long) r.getObject("duration_millis"), (Integer) r.getObject("width"), (Integer) r.getObject("height"), date == null ? null : date.toInstant());
        }, id, jwt.getSubject());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.getFirst());
    }
}
