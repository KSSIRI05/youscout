package ma.youscout.video;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Marks an owned video as deleted and records its VideoDeleted event in one MongoDB transaction. */
@Service
class DeletionService {
    private final MongoTemplate mongo;
    private final OutboxRepository outbox;

    DeletionService(MongoTemplate mongo, OutboxRepository outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public void delete(UUID id, String owner) {
        Query owned = Query.query(Criteria.where("_id").is(id.toString()).and("ownerSubject").is(owner));
        Document current = mongo.findOne(owned, Document.class, "videos");
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        // A repeated call finds the deletion already recorded and emits nothing new.
        if (current.get("deletedAt") != null) return;

        Instant now = Instant.now();
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(id.toString())
                        .and("ownerSubject").is(owner).and("deletedAt").is(null)),
                new Update().set("deletedAt", Date.from(now)).set("updatedAt", Date.from(now)), "videos");
        if (result.getModifiedCount() == 1) outbox.enqueueDeleted(id, owner);
    }
}
