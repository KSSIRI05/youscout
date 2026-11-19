package ma.youscout.video;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * U22 : supprime toutes les vidéos d'un compte fermé, quel que soit leur état. Chaque vidéo passe par la même
 * suppression que celle du propriétaire : VideoDeleted retire le clip du fil et déclenche le nettoyage S3 du worker.
 * Appelée par User avec son compte de service (rôle account-closure) ; aucune route Gateway n'y mène. Rejouable.
 */
@RestController
class AccountPurgeController {
    private final MongoTemplate mongo;
    private final DeletionService deletions;

    AccountPurgeController(MongoTemplate mongo, DeletionService deletions) {
        this.mongo = mongo;
        this.deletions = deletions;
    }

    @DeleteMapping("/internal/accounts/{subject}")
    ResponseEntity<Void> purge(@PathVariable String subject) {
        if (subject.isBlank() || subject.length() > 255 || subject.indexOf('\0') >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant de compte invalide.");
        var remaining = query(where("ownerSubject").is(subject).and("deletedAt").is(null));
        remaining.fields().include("_id");
        for (Document video : mongo.find(remaining, Document.class, "videos"))
            deletions.delete(UUID.fromString(video.getString("_id")), subject);
        return ResponseEntity.noContent().build();
    }
}
