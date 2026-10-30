package ma.youscout.video;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import ma.youscout.video.storage.S3StorageProperties;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.exception.SdkException;
import org.springframework.jdbc.core.RowMapper;
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
    private final S3Presigner presigner;
    private final S3StorageProperties storage;
    VideoDetailsController(JdbcTemplate jdbc, S3Presigner presigner, S3StorageProperties storage) {
        this.jdbc = jdbc; this.presigner = presigner; this.storage = storage;
    }
    private final RowMapper<Details> mapper = (r, n) -> {
        var date = r.getTimestamp("processing_updated_at");
        return new Details(r.getObject("id", UUID.class), r.getString("title"), r.getString("status"), r.getString("processing_status"), r.getString("processing_error"),
            (Long) r.getObject("duration_millis"), (Integer) r.getObject("width"), (Integer) r.getObject("height"), date == null ? null : date.toInstant());
    };
    record Page(List<Details> items, boolean hasMore) {}
    @GetMapping
    ResponseEntity<Page> list(@RequestParam(defaultValue = "0") int offset, @AuthenticationPrincipal Jwt jwt) {
        if (offset < 0 || offset > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination invalide.");
        var rows = jdbc.query("SELECT * FROM videos WHERE owner_subject = ? ORDER BY created_at DESC, id DESC LIMIT 21 OFFSET ?",
            mapper, jwt.getSubject(), offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Page(rows.subList(0, Math.min(20, rows.size())), rows.size() > 20 && offset + 20 <= 10000));
    }
    @GetMapping("/{id}")
    ResponseEntity<Details> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var rows = jdbc.query("SELECT * FROM videos WHERE id = ? AND owner_subject = ?", mapper, id, jwt.getSubject());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.getFirst());
    }

    record Playback(String videoUrl, String thumbnailUrl, Instant expiresAt) {}
    @GetMapping("/{id}/playback")
    ResponseEntity<Playback> playback(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        // Le contrôle de propriété précède toute signature, y compris pour un administrateur.
        var rows = jdbc.queryForList("SELECT * FROM videos WHERE id = ? AND owner_subject = ?", id, jwt.getSubject());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        var row = rows.getFirst();
        if (!"READY".equals(row.get("processing_status")) || !"UPLOADED".equals(row.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vidéo pas encore disponible pour la lecture.");
        String videoKey = (String) row.get("processed_video_key"), thumbnailKey = (String) row.get("thumbnail_key");
        String prefix = "processed/" + id + "/";
        if (!storage.bucket().equals(row.get("s3_bucket")) || videoKey == null || thumbnailKey == null
                || !videoKey.matches(prefix + "[0-9a-f-]{36}/video\\.mp4")
                || !thumbnailKey.equals(videoKey.substring(0, videoKey.lastIndexOf('/') + 1) + "thumbnail.jpg"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Média temporairement indisponible.");
        try {
            var video = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(120))
                .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(videoKey).build()).build());
            var thumbnail = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(120))
                .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(thumbnailKey).build()).build());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Playback(video.url().toExternalForm(),
                thumbnail.url().toExternalForm(), video.expiration().isBefore(thumbnail.expiration()) ? video.expiration() : thumbnail.expiration()));
        } catch (SdkException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lecture temporairement indisponible.");
        }
    }
}
