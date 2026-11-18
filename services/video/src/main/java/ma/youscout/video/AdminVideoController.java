package ma.youscout.video;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Vue et actions de modération réservées au rôle admin (Gateway et SecurityConfig). */
@RestController
@RequestMapping("/v1/videos/admin")
class AdminVideoController {
    record AdminVideo(UUID id, String title, String ownerSubject, String uploadStatus, String processingStatus,
                      String publicationStatus, Instant publishedAt, String moderationStatus, Instant moderatedAt,
                      String moderatedBy, String moderationNote) {}

    private final MongoTemplate mongo;
    private final ModerationService moderation;
    private final VideoDetailsController details;

    AdminVideoController(MongoTemplate mongo, ModerationService moderation, VideoDetailsController details) {
        this.mongo = mongo;
        this.moderation = moderation;
        this.details = details;
    }

    @GetMapping("/{id}")
    ResponseEntity<AdminVideo> get(@PathVariable UUID id) {
        return noStore(view(find(id)));
    }

    /** Lecture temporaire pour examiner un signalement, y compris une vidéo retirée. */
    @GetMapping("/{id}/playback")
    ResponseEntity<VideoDetailsController.Playback> playback(@PathVariable UUID id) {
        return details.signedPlayback(id, find(id));
    }

    @PostMapping("/{id}/takedown")
    ResponseEntity<AdminVideo> takeDown(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return noStore(view(moderation.takeDown(id, jwt.getSubject(), note(body))));
    }

    @PostMapping("/{id}/restore")
    ResponseEntity<AdminVideo> restore(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return noStore(view(moderation.restore(id, jwt.getSubject(), note(body))));
    }

    private Document find(UUID id) {
        Document video = mongo.findOne(Query.query(Criteria.where("_id").is(id.toString()).and("deletedAt").is(null)), Document.class, "videos");
        if (video == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        return video;
    }

    private static String note(Map<String, Object> body) {
        Object value = body == null ? null : body.get("note");
        if (body != null && !body.keySet().stream().allMatch("note"::equals)) throw UploadInput.invalid("Seul le champ note est accepté.");
        if (value == null) return null;
        if (!(value instanceof String text) || text.strip().length() > 500 || text.indexOf('\0') >= 0)
            throw UploadInput.invalid("La note doit contenir au plus 500 caractères.");
        return text.strip().isEmpty() ? null : text.strip();
    }

    private static AdminVideo view(Document video) {
        return new AdminVideo(UUID.fromString(video.getString("id")), video.getString("title"), video.getString("ownerSubject"),
                video.getString("status"), video.getString("processingStatus"), video.getString("publicationStatus"),
                VideoRepository.instant(video.get("publishedAt")), ModerationService.status(video),
                VideoRepository.instant(video.get("moderatedAt")), video.getString("moderatedBy"), video.getString("moderationNote"));
    }

    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
