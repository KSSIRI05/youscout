package ma.youscout.video;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Applies each worker result once and records rejected Kafka offsets idempotently. */
@Repository
class ProcessingResultStore {
    private final MongoTemplate mongo;

    ProcessingResultStore(MongoTemplate mongo) { this.mongo = mongo; }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public void accept(ProcessingResult result, String topic, int partition, long offset) {
        String videoId = result.videoId().toString();
        Document video = mongo.findById(videoId, Document.class, "videos");
        Query source = Query.query(Criteria.where("_id").is(result.sourceEventId().toString())
                .and("aggregateId").is(videoId).and("eventType").is("VideoUploaded"));
        if (video == null || !mongo.exists(source, "video_outbox")) {
            reject(topic, partition, offset, "UNKNOWN_SOURCE");
            return;
        }

        Document seen = mongo.findById(result.eventId().toString(), Document.class, "processing_result_inbox");
        if (seen != null) {
            if (!result.fingerprint().equals(seen.getString("fingerprint")))
                reject(topic, partition, offset, "EVENT_ID_CONFLICT");
            return;
        }
        if (!"UPLOADED".equals(video.getString("status"))
                || !result.etag().equals(video.getString("sourceETag"))
                || !result.bucket().equals(video.getString("s3Bucket"))) {
            reject(topic, partition, offset, "SOURCE_MISMATCH");
            return;
        }
        if ("READY".equals(video.getString("processingStatus")) || "FAILED".equals(video.getString("processingStatus"))) {
            reject(topic, partition, offset, "ALREADY_FINALIZED");
            return;
        }

        Instant now = Instant.now();
        Query processing = Query.query(Criteria.where("_id").is(videoId).and("status").is("UPLOADED")
                .and("sourceETag").is(result.etag()).and("s3Bucket").is(result.bucket())
                .and("processingStatus").nin("READY", "FAILED"));
        var updateResult = mongo.updateFirst(processing, new Update()
                .set("processingStatus", result.success() ? "READY" : "FAILED")
                .set("processingUpdatedAt", Date.from(now)).set("processingError", result.error())
                .set("processedVideoKey", result.videoKey()).set("thumbnailKey", result.thumbnailKey())
                .set("durationMillis", result.durationMillis()).set("width", result.width()).set("height", result.height()),
                "videos");
        if (updateResult.getModifiedCount() != 1) {
            reject(topic, partition, offset, "ALREADY_FINALIZED");
            return;
        }

        Document inbox = new Document("_id", result.eventId().toString()).append("eventId", result.eventId().toString())
                .append("videoId", videoId).append("fingerprint", result.fingerprint()).append("appliedAt", Date.from(now));
        mongo.insert(inbox, "processing_result_inbox");
    }

    @Transactional(transactionManager = "mongoTransactionManager", timeout = 10)
    public void reject(String topic, int partition, long offset, String reason) {
        String id = topic + ":" + partition + ":" + offset;
        if (mongo.exists(Query.query(Criteria.where("_id").is(id)), "rejected_processing_results")) return;
        Document rejected = new Document("_id", id).append("topic", topic).append("partitionId", partition)
                .append("recordOffset", offset).append("reason", reason).append("createdAt", new Date());
        mongo.insert(rejected, "rejected_processing_results");
    }
}
