package ma.youscout.video;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional event log; event IDs remain stable across retries for downstream deduplication. */
@Repository
class OutboxRepository {
    record Event(UUID id, UUID aggregateId, String eventType, String payload, int attempts) {}
    private final MongoTemplate mongo;

    OutboxRepository(MongoTemplate mongo) { this.mongo = mongo; }

    @Transactional(transactionManager = "mongoTransactionManager", propagation = Propagation.MANDATORY)
    public void enqueue(UUID videoId) {
        Document video = mongo.findById(videoId.toString(), Document.class, "videos");
        if (video == null || !"UPLOADED".equals(video.getString("status")))
            throw new IllegalStateException("Confirmed video required for outbox.");
        Instant now = Instant.now();
        Document data = new Document("videoId", videoId.toString()).append("s3Bucket", video.getString("s3Bucket"))
                .append("s3Key", video.getString("s3Key")).append("sourceETag", video.getString("sourceETag"))
                .append("contentType", video.getString("contentType")).append("sizeBytes", video.get("sizeBytes"));
        insert(videoId, "VideoUploaded", envelope(UUID.randomUUID(), "VideoUploaded", 1,
                VideoRepository.instant(video.get("completedAt")), data), now);
    }

    @Transactional(transactionManager = "mongoTransactionManager", propagation = Propagation.MANDATORY)
    public void enqueuePublished(UUID videoId, String owner) {
        Document video = mongo.findById(videoId.toString(), Document.class, "videos");
        if (video == null || !owner.equals(video.getString("ownerSubject"))
                || !"UPLOADED".equals(video.getString("status"))
                || !"READY".equals(video.getString("processingStatus"))
                || !"PUBLISHED".equals(video.getString("publicationStatus")))
            throw new IllegalStateException("Published video required for outbox.");
        Instant publishedAt = VideoRepository.instant(video.get("publishedAt"));
        Document data = new Document("videoId", videoId.toString()).append("ownerSubject", owner)
                .append("title", video.getString("title")).append("publishedAt", publishedAt.toString())
                .append("skills", eventReferences(video, "skills"))
                .append("hashtags", eventReferences(video, "hashtags"));
        insert(videoId, "VideoPublished", envelope(UUID.randomUUID(), "VideoPublished", 2, publishedAt, data),
                Instant.now());
    }

    private Document envelope(UUID eventId, String type, int version, Instant occurredAt, Document data) {
        return new Document("eventId", eventId.toString()).append("eventType", type)
                .append("eventVersion", version).append("occurredAt", occurredAt.toString())
                .append("producer", "video-service").append("data", data);
    }

    private List<Document> eventReferences(Document video, String field) {
        Object value = video.get(field);
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(item -> (Document) item)
                .map(item -> new Document("id", item.getString("id")).append("name", item.getString("name")))
                .toList();
    }

    private void insert(UUID videoId, String type, Document payload, Instant createdAt) {
        UUID eventId = UUID.fromString(payload.getString("eventId"));
        Document event = new Document("_id", eventId.toString()).append("id", eventId.toString())
                .append("aggregateId", videoId.toString()).append("eventType", type).append("payload", payload)
                .append("createdAt", VideoRepository.date(createdAt)).append("publishedAt", null)
                .append("attempts", 0).append("nextAttemptAt", VideoRepository.date(createdAt)).append("lastError", null);
        mongo.insert(event, "video_outbox");
    }

    Optional<Event> lockNext() {
        Instant now = Instant.now();
        Criteria available = new Criteria().orOperator(Criteria.where("lockedUntil").is(null),
                Criteria.where("lockedUntil").lt(VideoRepository.date(now)));
        Query query = new Query(new Criteria().andOperator(Criteria.where("publishedAt").is(null),
                Criteria.where("nextAttemptAt").lte(VideoRepository.date(now)), available))
                .with(Sort.by(Sort.Order.asc("nextAttemptAt"), Sort.Order.asc("createdAt"), Sort.Order.asc("_id")));
        Document event = mongo.findAndModify(query, new Update().set("lockedUntil", VideoRepository.date(now.plusSeconds(60))),
                FindAndModifyOptions.options().returnNew(true), Document.class, "video_outbox");
        return Optional.ofNullable(event).map(OutboxRepository::toEvent);
    }

    void published(UUID id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id.toString())),
                new Update().set("publishedAt", new Date()).set("lastError", null).unset("lockedUntil"), "video_outbox");
    }

    void retry(Event event) {
        long delaySeconds = Math.min(300, 1L << Math.min(9, event.attempts()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(event.id().toString())),
                new Update().inc("attempts", 1).set("nextAttemptAt", VideoRepository.date(Instant.now().plusSeconds(delaySeconds)))
                        .set("lastError", "KAFKA_UNAVAILABLE").unset("lockedUntil"), "video_outbox");
    }

    private static Event toEvent(Document doc) {
        Document payload = doc.get("payload", Document.class);
        return new Event(UUID.fromString(doc.getString("id")), UUID.fromString(doc.getString("aggregateId")),
                doc.getString("eventType"), payload.toJson(), ((Number) doc.get("attempts")).intValue());
    }
}
