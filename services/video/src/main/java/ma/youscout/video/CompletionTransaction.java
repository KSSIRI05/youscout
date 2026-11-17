package ma.youscout.video;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Applies a confirmed upload and its outbox event atomically in MongoDB. */
@Service
class CompletionTransaction {
    private final VideoRepository videos;
    private final OutboxRepository outbox;

    CompletionTransaction(VideoRepository videos, OutboxRepository outbox) {
        this.videos = videos;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    CompletionService.Confirmation confirm(UUID id, String owner, String etag, String leaseToken) {
        var current = videos.lockOwned(id, owner).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable."));
        if ("UPLOADED".equals(current.status())) return response(current);
        if (!"PENDING_UPLOAD".equals(current.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette vidéo ne peut pas être confirmée.");

        var confirmed = videos.confirm(id, owner, etag, leaseToken);
        if (confirmed == null) {
            var latest = videos.lockOwned(id, owner).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable."));
            if ("UPLOADED".equals(latest.status())) return response(latest);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette vidéo ne peut pas être confirmée.");
        }
        outbox.enqueue(confirmed.id());
        return response(confirmed);
    }

    private CompletionService.Confirmation response(VideoUpload video) {
        return new CompletionService.Confirmation(video.id(), video.status(), video.title(), video.sizeBytes(),
                video.createdAt(), video.updatedAt(), video.completedAt());
    }
}
