package ma.youscout.video;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Stores catalog snapshots in the owning video document. */
@Repository
class VideoMetadataRepository {
    private final MongoTemplate mongo;

    VideoMetadataRepository(MongoTemplate mongo) { this.mongo = mongo; }

    void save(UUID videoId, List<CatalogReference> skills, List<CatalogReference> hashtags) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(videoId.toString())),
                new Update().set("skills", embedded(skills)).set("hashtags", embedded(hashtags)), "videos");
    }

    boolean matches(UUID videoId, List<UUID> skills, List<UUID> hashtags) {
        Document video = mongo.findById(videoId.toString(), Document.class, "videos");
        return video != null && ids(video, "skills").equals(skills) && ids(video, "hashtags").equals(hashtags);
    }

    List<CatalogReference> skills(UUID videoId) { return references(videoId, "skills"); }
    List<CatalogReference> hashtags(UUID videoId) { return references(videoId, "hashtags"); }

    private List<CatalogReference> references(UUID videoId, String field) {
        Document video = mongo.findById(videoId.toString(), Document.class, "videos");
        if (video == null) return List.of();
        List<CatalogReference> result = new ArrayList<>();
        for (Document ref : documents(video, field)) result.add(new CatalogReference(
                UUID.fromString(ref.getString("id")), ref.getString("name")));
        return List.copyOf(result);
    }

    private List<UUID> ids(Document video, String field) {
        return documents(video, field).stream().map(ref -> UUID.fromString(ref.getString("id"))).toList();
    }

    private List<Document> embedded(List<CatalogReference> references) {
        List<Document> result = new ArrayList<>(references.size());
        for (int i = 0; i < references.size(); i++) {
            CatalogReference ref = references.get(i);
            result.add(new Document("id", ref.id().toString()).append("name", ref.name()).append("position", i));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Document> documents(Document video, String field) {
        Object value = video.get(field);
        return value instanceof List<?> list ? (List<Document>) list : List.of();
    }
}
