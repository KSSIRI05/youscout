package ma.youscout.video;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Confirme les métadonnées observées dans S3 ; ne valide pas le format réel du média. */
@Service
class CompletionService {
    record Confirmation(UUID id, String status, String title, long sizeBytes,
                        Instant createdAt, Instant updatedAt, Instant completedAt) {}
    private final VideoRepository videos;
    private final S3Client s3;
    private final OutboxRepository outbox;

    CompletionService(VideoRepository videos, S3Client s3, OutboxRepository outbox) {
        this.videos = videos; this.s3 = s3; this.outbox = outbox;
    }

    @Transactional
    public Confirmation complete(UUID id, String owner) {
        var video = videos.lockOwned(id, owner).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable."));
        // Une reprise ne réécrit ni date ni ETag, et fonctionne même si S3 est indisponible ensuite.
        if (video.status().equals("UPLOADED")) return response(video);
        if (!video.status().equals("PENDING_UPLOAD")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette vidéo ne peut pas être confirmée.");
        }
        var object = inspect(video);
        if (object.contentLength() == null || object.contentLength() != video.sizeBytes()
                || !video.contentType().equals(object.contentType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Taille ou type déclaré du fichier incorrect.");
        }
        if (object.eTag() == null || object.eTag().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Métadonnées S3 temporairement indisponibles.");
        }
        var confirmed = videos.confirm(video.id(), owner, object.eTag());
        // Même transaction JDBC : une panne d'outbox annule aussi le passage à UPLOADED.
        outbox.enqueue(confirmed.id());
        return response(confirmed);
    }

    private HeadObjectResponse inspect(VideoUpload video) {
        try {
            // Utilise uniquement le bucket et la clé persistés, jamais une URL ou une clé du client.
            return s3.headObject(HeadObjectRequest.builder().bucket(video.bucket()).key(video.key()).build());
        } catch (S3Exception error) {
            // Sans ListBucket, S3 masque aussi un objet absent par 403 ; ne jamais confirmer dans ce cas.
            if (error.statusCode() == 403 || error.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Fichier absent ou inaccessible dans S3.");
            }
            throw unavailable();
        } catch (SdkException error) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        // Aucun message SDK, jeton, bucket ou clé n'est renvoyé au client.
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Vérification S3 temporairement indisponible.");
    }

    private Confirmation response(VideoUpload video) {
        return new Confirmation(video.id(), video.status(), video.title(), video.sizeBytes(),
                video.createdAt(), video.updatedAt(), video.completedAt());
    }
}
