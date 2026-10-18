package ma.youscout.video;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ma.youscout.video.storage.S3StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** Une URL n'est renvoyée qu'après persistance réussie ; un échec de signature annule la création. */
@Service
@EnableConfigurationProperties(UploadProperties.class)
class UploadService {
    record UploadTarget(String method, String url, Map<String, String> headers, Instant expiresAt) {}
    record Ticket(UUID id, String status, String title, long sizeBytes, Instant createdAt, UploadTarget upload) {}
    record Result(boolean created, Ticket ticket) {}
    private final VideoRepository videos;
    private final S3StorageProperties storage;
    private final UploadProperties limits;
    private final S3Presigner presigner;

    UploadService(VideoRepository videos, S3StorageProperties storage, UploadProperties limits, S3Presigner presigner) {
        this.videos = videos; this.storage = storage; this.limits = limits; this.presigner = presigner;
    }

    @Transactional
    public Result prepare(String owner, String idempotencyKey, Map<String, Object> fields) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{16,128}")) {
            throw UploadInput.invalid("Idempotency-Key requis : 16 à 128 caractères alphanumériques, tiret ou underscore.");
        }
        UploadInput input = UploadInput.from(fields, limits.maxSizeBytes());
        UUID id = UUID.randomUUID();
        boolean created = videos.create(id, owner, input, storage.bucket(), "videos/" + id + "/source", idempotencyKey);
        VideoUpload video = videos.find(owner, idempotencyKey);
        if (!video.title().equals(input.title()) || !video.contentType().equals(input.contentType())
                || video.sizeBytes() != input.sizeBytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette clé d'idempotence correspond à un autre envoi.");
        }
        if (!video.status().equals("PENDING_UPLOAD")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cet envoi n'est plus en attente.");
        }
        try {
            // Type et longueur font partie de la signature ; aucune ACL publique ni nom client dans la clé.
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(limits.urlLifetimeSeconds()))
                    .putObjectRequest(PutObjectRequest.builder().bucket(video.bucket()).key(video.key())
                            .contentType(video.contentType()).contentLength(video.sizeBytes()).build()).build());
            var target = new UploadTarget("PUT", signed.url().toExternalForm(),
                    Map.of("Content-Type", video.contentType(), "Content-Length", Long.toString(video.sizeBytes())),
                    signed.expiration());
            return new Result(created, new Ticket(video.id(), video.status(), video.title(), video.sizeBytes(), video.createdAt(), target));
        } catch (SdkException error) {
            // Ne jamais renvoyer/journaliser l'erreur SDK : elle peut inclure des informations d'identification.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Signature S3 temporairement indisponible.");
        }
    }
}
