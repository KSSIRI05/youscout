package ma.youscout.video;

import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Ensures uniqueness and query indexes for both fresh installs and migrated MongoDB data. */
@Component
class VideoMongoIndexes implements ApplicationRunner {
    static final IndexOptions ONCE_PER_VIDEO = new IndexOptions().unique(true).name("video_outbox_once_per_video_unique")
            .partialFilterExpression(new Document("eventType", new Document("$in", java.util.List.of("VideoUploaded", "VideoPublished", "VideoDeleted"))));
    private final MongoTemplate mongo;

    VideoMongoIndexes(MongoTemplate mongo) { this.mongo = mongo; }

    @Override
    public void run(ApplicationArguments args) {
        for (String collection : java.util.List.of("videos", "video_outbox", "processing_result_inbox",
                "rejected_processing_results")) {
            if (!mongo.collectionExists(collection)) mongo.createCollection(collection);
        }
        mongo.getCollection("videos").createIndex(new Document("ownerSubject", 1)
                .append("createdAt", -1).append("_id", -1), new IndexOptions().name("video_owner_created"));
        mongo.getCollection("videos").createIndex(new Document("ownerSubject", 1).append("idempotencyKey", 1),
                new IndexOptions().unique(true).name("video_owner_idempotency_unique"));
        mongo.getCollection("videos").createIndex(new Document("s3Bucket", 1).append("s3Key", 1),
                new IndexOptions().unique(true).name("video_s3_object_unique"));
        // Upload, publication et suppression n'ont lieu qu'une fois par vidéo ; la modération peut se répéter.
        // L'ancien index unique sans filtre est remplacé sur les bases existantes.
        for (Document index : mongo.getCollection("video_outbox").listIndexes())
            if ("video_outbox_aggregate_type_unique".equals(index.getString("name"))) mongo.getCollection("video_outbox").dropIndex("video_outbox_aggregate_type_unique");
        mongo.getCollection("video_outbox").createIndex(new Document("aggregateId", 1).append("eventType", 1), ONCE_PER_VIDEO);
        mongo.getCollection("video_outbox").createIndex(new Document("publishedAt", 1)
                .append("nextAttemptAt", 1).append("createdAt", 1), new IndexOptions().name("video_outbox_pending"));
    }
}
