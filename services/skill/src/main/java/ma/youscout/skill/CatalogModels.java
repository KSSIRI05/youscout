package ma.youscout.skill;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
record Skill(UUID id, String name, String description, boolean active, Instant createdAt, Instant updatedAt) {}
record Hashtag(UUID id, String name, Instant createdAt) {}
record CatalogPage<T>(List<T> items, int limit, int offset) {}
record EnsuredHashtag(Hashtag hashtag, boolean created) {}
