package ma.youscout.video;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Publishes a ready video and its Kafka outbox event in one MongoDB transaction. */
@Service
class PublicationService {
    record Publication(UUID id, String publicationStatus, Instant publishedAt) {}
    private final MongoTemplate mongo;
    private final OutboxRepository outbox;

    PublicationService(MongoTemplate mongo, OutboxRepository outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public Publication publish(UUID id, String owner) {
        Document current = findOwned(id, owner);
        if (current == null) throw notFound();
        if ("PUBLISHED".equals(current.getString("publicationStatus"))) return publication(current);
        if (!"UPLOADED".equals(current.getString("status")) || !"READY".equals(current.getString("processingStatus")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une vidéo prête peut être publiée.");

        Instant now = Instant.now();
        Query unpublished = Query.query(Criteria.where("_id").is(id.toString()).and("ownerSubject").is(owner)
                .and("status").is("UPLOADED").and("processingStatus").is("READY")
                .and("publicationStatus").is("UNPUBLISHED"));
        Document published = mongo.findAndModify(unpublished,
                new Update().set("publicationStatus", "PUBLISHED").set("publishedAt", Date.from(now)),
                FindAndModifyOptions.options().returnNew(true), Document.class, "videos");
        if (published == null) {
            Document latest = findOwned(id, owner);
            if (latest == null) throw notFound();
            if ("PUBLISHED".equals(latest.getString("publicationStatus"))) return publication(latest);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une vidéo prête peut être publiée.");
        }
        outbox.enqueuePublished(id, owner);
        return publication(published);
    }

    private Document findOwned(UUID id, String owner) {
        return mongo.findOne(Query.query(Criteria.where("_id").is(id.toString()).and("ownerSubject").is(owner)),
                Document.class, "videos");
    }

    private Publication publication(Document video) {
        return new Publication(UUID.fromString(video.getString("id")), video.getString("publicationStatus"),
                VideoRepository.instant(video.get("publishedAt")));
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
    }
}
