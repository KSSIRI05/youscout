package ma.youscout.video;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Persists Video documents and applies state changes with atomic MongoDB updates. */
@Repository
class VideoRepository {
    private final MongoTemplate mongo;

    VideoRepository(MongoTemplate mongo) { this.mongo = mongo; }

    boolean create(UUID id, String owner, UploadInput input, String bucket, String key, String idempotencyKey) {
        Instant now = Instant.now();
        Query keyQuery = Query.query(Criteria.where("ownerSubject").is(owner).and("idempotencyKey").is(idempotencyKey));
        Update insert = new Update().setOnInsert("_id", id.toString()).setOnInsert("id", id.toString())
                .setOnInsert("ownerSubject", owner).setOnInsert("title", input.title())
                .setOnInsert("s3Bucket", bucket).setOnInsert("s3Key", key)
                .setOnInsert("contentType", input.contentType()).setOnInsert("sizeBytes", input.sizeBytes())
                .setOnInsert("status", "PENDING_UPLOAD").setOnInsert("idempotencyKey", idempotencyKey)
                .setOnInsert("createdAt", date(now)).setOnInsert("updatedAt", date(now))
                .setOnInsert("processingStatus", null).setOnInsert("processingUpdatedAt", null)
                .setOnInsert("processingError", null).setOnInsert("processedVideoKey", null)
                .setOnInsert("thumbnailKey", null).setOnInsert("durationMillis", null)
                .setOnInsert("width", null).setOnInsert("height", null)
                .setOnInsert("publicationStatus", "UNPUBLISHED").setOnInsert("publishedAt", null)
                .setOnInsert("skills", java.util.List.of()).setOnInsert("hashtags", java.util.List.of());
        Document video = mongo.findAndModify(keyQuery, insert, FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class, "videos");
        return id.toString().equals(video.getString("id"));
    }

    VideoUpload find(String owner, String idempotencyKey) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("ownerSubject").is(owner)
                .and("idempotencyKey").is(idempotencyKey)), Document.class, "videos"))
                .map(VideoRepository::toUpload).orElse(null);
    }

    Optional<VideoUpload> lockOwned(UUID id, String owner) {
        // A Mongo transaction protects the later conditional update; Mongo has no row-level FOR UPDATE lock.
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("_id").is(id.toString())
                .and("ownerSubject").is(owner)), Document.class, "videos")).map(VideoRepository::toUpload);
    }

    Optional<VideoUpload> claimCompletion(UUID id, String owner, String token) {
        Instant now = Instant.now();
        Criteria available = new Criteria().orOperator(Criteria.where("completionLeaseUntil").is(null),
                Criteria.where("completionLeaseUntil").lt(date(now)));
        Query pending = Query.query(new Criteria().andOperator(Criteria.where("_id").is(id.toString())
                .and("ownerSubject").is(owner).and("status").is("PENDING_UPLOAD"), available));
        Update lease = new Update().set("completionLeaseToken", token)
                .set("completionLeaseUntil", date(now.plusSeconds(60)));
        return Optional.ofNullable(mongo.findAndModify(pending, lease, FindAndModifyOptions.options().returnNew(true),
                Document.class, "videos")).map(VideoRepository::toUpload);
    }

    void releaseCompletion(UUID id, String owner, String token) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id.toString()).and("ownerSubject").is(owner)
                        .and("completionLeaseToken").is(token)),
                new Update().unset("completionLeaseToken").unset("completionLeaseUntil"), "videos");
    }

    VideoUpload confirm(UUID id, String owner, String etag, String leaseToken) {
        Instant now = Instant.now();
        Query pending = Query.query(Criteria.where("_id").is(id.toString())
                .and("ownerSubject").is(owner).and("status").is("PENDING_UPLOAD")
                .and("completionLeaseToken").is(leaseToken));
        Update update = new Update().set("status", "UPLOADED").set("sourceETag", etag)
                .set("completedAt", date(now)).set("updatedAt", date(now))
                .set("processingStatus", "WAITING").set("processingUpdatedAt", date(now))
                .unset("completionLeaseToken").unset("completionLeaseUntil");
        return Optional.ofNullable(mongo.findAndModify(pending, update, FindAndModifyOptions.options().returnNew(true),
                Document.class, "videos")).map(VideoRepository::toUpload).orElse(null);
    }

    static VideoUpload toUpload(Document doc) {
        return new VideoUpload(UUID.fromString(doc.getString("id")), doc.getString("ownerSubject"),
                doc.getString("title"), doc.getString("s3Bucket"), doc.getString("s3Key"),
                doc.getString("contentType"), ((Number) doc.get("sizeBytes")).longValue(), doc.getString("status"),
                instant(doc.get("createdAt")), instant(doc.get("updatedAt")), instant(doc.get("completedAt")),
                doc.getString("sourceETag"));
    }

    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("Unexpected persisted timestamp type.");
    }

    static Date date(Instant value) { return Date.from(value); }
}
