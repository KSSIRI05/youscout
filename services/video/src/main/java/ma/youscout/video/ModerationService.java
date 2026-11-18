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

/**
 * A04 : retrait et rétablissement d'une vidéo par un administrateur. Les fichiers sont conservés ;
 * chaque changement incrémente moderationVersion et écrit VideoModerated dans la même transaction.
 */
@Service
class ModerationService {
    static final String REMOVED = "REMOVED", ACTIVE = "ACTIVE";
    private final MongoTemplate mongo;
    private final OutboxRepository outbox;

    ModerationService(MongoTemplate mongo, OutboxRepository outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public Document takeDown(UUID id, String admin, String note) { return change(id, admin, note, REMOVED); }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public Document restore(UUID id, String admin, String note) { return change(id, admin, note, ACTIVE); }

    private Document change(UUID id, String admin, String note, String target) {
        Query existing = Query.query(Criteria.where("_id").is(id.toString()).and("deletedAt").is(null));
        Document current = mongo.findOne(existing, Document.class, "videos");
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vidéo introuvable.");
        // Un appel répété ne change rien et n'émet aucun événement.
        if (target.equals(status(current))) return current;

        Instant now = Instant.now();
        Criteria unchanged = target.equals(REMOVED) ? Criteria.where("moderationStatus").ne(REMOVED) : Criteria.where("moderationStatus").is(REMOVED);
        Document changed = mongo.findAndModify(Query.query(Criteria.where("_id").is(id.toString()).and("deletedAt").is(null)
                        .andOperator(unchanged)),
                new Update().set("moderationStatus", target).inc("moderationVersion", 1).set("moderatedAt", Date.from(now))
                        .set("moderatedBy", admin).set("moderationNote", note).set("updatedAt", Date.from(now)),
                FindAndModifyOptions.options().returnNew(true), Document.class, "videos");
        if (changed == null) return mongo.findOne(existing, Document.class, "videos");
        outbox.enqueueModerated(id);
        return changed;
    }

    static String status(Document video) {
        return REMOVED.equals(video.getString("moderationStatus")) ? REMOVED : ACTIVE;
    }
}
