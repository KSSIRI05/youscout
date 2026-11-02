package ma.youscout.video.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Copies a consistent PostgreSQL snapshot into MongoDB without changing the live Video repository. */
@Service
@ConditionalOnProperty(prefix = "youscout.migration.postgres-to-mongo", name = "enabled", havingValue = "true")
public class PostgresVideoSnapshotImporter {
    private static final String RUN_ID = "postgres-video-v1";

    private final JdbcTemplate jdbc;
    private final MongoTemplate mongo;

    public PostgresVideoSnapshotImporter(JdbcTemplate jdbc, MongoTemplate mongo) {
        this.jdbc = jdbc;
        this.mongo = mongo;
    }

    /** PostgreSQL remains the authority; Mongo writes are repeatable until the final marker is stored. */
    @Transactional(transactionManager = "postgresTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Long> importSnapshot() {
        Document run = mongo.getCollection("video_migration_runs").find(new Document("_id", RUN_ID)).first();
        if (run != null && "COMPLETED".equals(run.getString("status"))) {
            throw new IllegalStateException("Video snapshot migration already completed; use a new migration version for another snapshot.");
        }
        Date startedAt = run == null ? new Date() : run.getDate("startedAt");
        if (run == null) {
            for (String collection : List.of("videos", "video_outbox", "processing_result_inbox", "rejected_processing_results")) {
                if (mongo.getCollection(collection).countDocuments() != 0) {
                    throw new IllegalStateException("Refusing to import into non-empty MongoDB collection: " + collection);
                }
            }
            mongo.getCollection("video_migration_runs").insertOne(new Document("_id", RUN_ID)
                    .append("status", "STARTED").append("startedAt", startedAt));
        }

        long videos = importVideos();
        long outbox = importOutbox();
        long inbox = importInbox();
        long rejected = importRejectedResults();
        createIndexes();

        Document marker = new Document("_id", RUN_ID)
                .append("status", "COMPLETED")
                .append("source", "postgresql")
                .append("target", "mongodb")
                .append("startedAt", startedAt)
                .append("completedAt", new Date())
                .append("counts", new Document("videos", videos).append("outboxEvents", outbox)
                        .append("processingInbox", inbox).append("rejectedProcessingResults", rejected));
        mongo.getCollection("video_migration_runs").replaceOne(new Document("_id", RUN_ID), marker);
        return Map.of("videos", videos, "outboxEvents", outbox, "processingInbox", inbox,
                "rejectedProcessingResults", rejected);
    }

    private long importVideos() {
        Map<String, List<Document>> skills = references("video_skills", "skill_id");
        Map<String, List<Document>> hashtags = references("video_hashtags", "hashtag_id");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM videos ORDER BY id");
        for (Map<String, Object> row : rows) {
            Document video = document(row);
            String id = video.getString("id");
            video.append("skills", skills.getOrDefault(id, List.of()))
                    .append("hashtags", hashtags.getOrDefault(id, List.of()));
            replace("videos", video);
        }
        return rows.size();
    }

    private Map<String, List<Document>> references(String table, String idColumn) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT video_id, " + idColumn + ", name, position FROM " + table + " ORDER BY video_id, position");
        Map<String, List<Document>> byVideo = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String videoId = normalize(row.get("video_id")).toString();
            Document reference = new Document("id", normalize(row.get(idColumn)).toString())
                    .append("name", row.get("name"))
                    .append("position", ((Number) row.get("position")).intValue());
            byVideo.computeIfAbsent(videoId, ignored -> new ArrayList<>()).add(reference);
        }
        return byVideo;
    }

    private long importOutbox() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at,
                       attempts, next_attempt_at, last_error
                FROM outbox_events ORDER BY id
                """);
        for (Map<String, Object> row : rows) {
            Document event = document(row);
            Object payload = row.get("payload");
            event.put("payload", payload == null ? null : Document.parse(payload.toString()));
            replace("video_outbox", event);
        }
        return rows.size();
    }

    private long importInbox() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, video_id, fingerprint, applied_at FROM processing_result_inbox ORDER BY event_id");
        for (Map<String, Object> row : rows) replace("processing_result_inbox", document(row));
        return rows.size();
    }

    private long importRejectedResults() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT topic, partition_id, record_offset, reason, created_at
                FROM rejected_processing_results ORDER BY topic, partition_id, record_offset
                """);
        for (Map<String, Object> row : rows) {
            Document rejected = document(row);
            rejected.put("_id", rejected.getString("topic") + ":" + rejected.getInteger("partitionId")
                    + ":" + rejected.getLong("recordOffset"));
            replace("rejected_processing_results", rejected);
        }
        return rows.size();
    }

    private void createIndexes() {
        mongo.getCollection("videos").createIndex(new Document("ownerSubject", 1)
                .append("createdAt", -1).append("_id", -1), new IndexOptions().name("video_owner_created"));
        mongo.getCollection("videos").createIndex(new Document("ownerSubject", 1).append("idempotencyKey", 1),
                new IndexOptions().unique(true).name("video_owner_idempotency_unique"));
        mongo.getCollection("videos").createIndex(new Document("s3Bucket", 1).append("s3Key", 1),
                new IndexOptions().unique(true).name("video_s3_object_unique"));
        mongo.getCollection("video_outbox").createIndex(new Document("aggregateId", 1).append("eventType", 1),
                new IndexOptions().unique(true).name("video_outbox_aggregate_type_unique"));
        mongo.getCollection("video_outbox").createIndex(new Document("publishedAt", 1)
                .append("nextAttemptAt", 1).append("createdAt", 1), new IndexOptions().name("video_outbox_pending"));
    }

    private void replace(String collection, Document document) {
        mongo.getCollection(collection).replaceOne(new Document("_id", document.get("_id")), document,
                new ReplaceOptions().upsert(true));
    }

    private Document document(Map<String, Object> row) {
        Document result = new Document();
        row.forEach((key, value) -> result.put(camelCase(key), normalize(value)));
        Object id = result.get("id");
        if (id == null) id = result.get("eventId");
        result.put("_id", id == null ? result.get("topic") + ":" + result.get("partitionId") + ":"
                + result.get("recordOffset") : id.toString());
        return result;
    }

    private String camelCase(String column) {
        StringBuilder result = new StringBuilder(column.length());
        boolean uppercaseNext = false;
        for (char character : column.toCharArray()) {
            if (character == '_') uppercaseNext = true;
            else {
                result.append(uppercaseNext ? Character.toUpperCase(character) : character);
                uppercaseNext = false;
            }
        }
        return result.toString();
    }

    private Object normalize(Object value) {
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof OffsetDateTime timestamp)
            return Date.from(timestamp.toInstant().truncatedTo(ChronoUnit.MILLIS));
        if (value instanceof Date date)
            return Date.from(date.toInstant().truncatedTo(ChronoUnit.MILLIS));
        return value;
    }
}
