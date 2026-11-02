package ma.youscout.video;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.youscout.video.storage.S3StorageProperties;
import org.bson.Document;
import org.springframework.data.domain.Sort;
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
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Private playback and metadata queries are constrained to the authenticated owner. */
@RestController
@RequestMapping("/v1/videos")
class VideoDetailsController {
    record Details(UUID id, String title, String uploadStatus, String processingStatus, String errorCode,
                   Long durationMillis, Integer width, Integer height, Instant processingUpdatedAt,
                   String publicationStatus, Instant publishedAt) {}
    record Page(List<Details> items, boolean hasMore) {}
    record Playback(String videoUrl, String thumbnailUrl, Instant expiresAt) {}

    private final MongoTemplate mongo;
    private final S3Presigner presigner;
    private final S3StorageProperties storage;

    VideoDetailsController(MongoTemplate mongo, S3Presigner presigner, S3StorageProperties storage) {
        this.mongo = mongo;
        this.presigner = presigner;
        this.storage = storage;
    }

    @GetMapping
    ResponseEntity<Page> list(@RequestParam(defaultValue = "0") int offset, @AuthenticationPrincipal Jwt jwt) {
        if (offset < 0 || offset > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination invalide.");
        Query query = Query.query(Criteria.where("ownerSubject").is(jwt.getSubject()))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"))).skip(offset).limit(21);
        List<Details> rows = mongo.find(query, Document.class, "videos").stream().map(VideoDetailsController::details).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(rows.subList(0, Math.min(20, rows.size())), rows.size() > 20 && offset + 20 <= 10000));
    }

    @GetMapping("/{id}")
    ResponseEntity<Details> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Document video = findOwned(id, jwt.getSubject());
        if (video == null) throw notFound("Vidéo introuvable.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(details(video));
    }

    @GetMapping("/{id}/playback")
    ResponseEntity<Playback> playback(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Document video = findOwned(id, jwt.getSubject());
        if (video == null) throw notFound("Vidéo introuvable.");
        return signedPlayback(id, video);
    }

    @GetMapping("/{id}/public-playback")
    ResponseEntity<Playback> publicPlayback(@PathVariable UUID id) {
        Document video = mongo.findOne(Query.query(Criteria.where("_id").is(id.toString())
                .and("publicationStatus").is("PUBLISHED")), Document.class, "videos");
        if (video == null) throw notFound("Vidéo publiée introuvable.");
        return signedPlayback(id, video);
    }

    private Document findOwned(UUID id, String owner) {
        return mongo.findOne(Query.query(Criteria.where("_id").is(id.toString()).and("ownerSubject").is(owner)),
                Document.class, "videos");
    }

    private ResponseEntity<Playback> signedPlayback(UUID id, Document row) {
        if (!"READY".equals(row.getString("processingStatus")) || !"UPLOADED".equals(row.getString("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vidéo pas encore disponible pour la lecture.");
        String videoKey = row.getString("processedVideoKey"), thumbnailKey = row.getString("thumbnailKey");
        String prefix = "processed/" + id + "/";
        if (!storage.bucket().equals(row.getString("s3Bucket")) || videoKey == null || thumbnailKey == null
                || !videoKey.matches(prefix + "[0-9a-f-]{36}/video\\.mp4")
                || !thumbnailKey.equals(videoKey.substring(0, videoKey.lastIndexOf('/') + 1) + "thumbnail.jpg"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Média temporairement indisponible.");
        try {
            var video = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(120))
                    .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(videoKey).build()).build());
            var thumbnail = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(120))
                    .getObjectRequest(GetObjectRequest.builder().bucket(storage.bucket()).key(thumbnailKey).build()).build());
            Instant expiresAt = video.expiration().isBefore(thumbnail.expiration()) ? video.expiration() : thumbnail.expiration();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Playback(
                    video.url().toExternalForm(), thumbnail.url().toExternalForm(), expiresAt));
        } catch (SdkException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lecture temporairement indisponible.");
        }
    }

    private static Details details(Document row) {
        return new Details(UUID.fromString(row.getString("id")), row.getString("title"), row.getString("status"),
                row.getString("processingStatus"), row.getString("processingError"), longValue(row.get("durationMillis")),
                intValue(row.get("width")), intValue(row.get("height")), VideoRepository.instant(row.get("processingUpdatedAt")),
                row.getString("publicationStatus"), VideoRepository.instant(row.get("publishedAt")));
    }

    private static Long longValue(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Integer intValue(Object value) { return value == null ? null : ((Number) value).intValue(); }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
