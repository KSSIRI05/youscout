package ma.youscout.feed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Valide le contrat VideoPublished v2 avant toute écriture métier. */
@Component
class PublishedVideoDecoder {
    static class InvalidEvent extends RuntimeException {
        InvalidEvent(String code) { super(code); }
    }
    private static final int MAX_EVENT_BYTES = 65536;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MAX_EVENT_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    PublishedVideo decode(String kafkaKey, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) invalid();
        try {
            Map<?, ?> root = mapper.readValue(raw, Map.class);
            if (root == null || !root.keySet().equals(Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data"))) invalid();
            if (!"VideoPublished".equals(root.get("eventType")) || !"video-service".equals(root.get("producer"))) invalid();
            if (!(root.get("eventVersion") instanceof Integer version) || version != 2) invalid();
            Object rawData = root.get("data");
            if (!(rawData instanceof Map<?, ?>)) invalid();
            Map<?, ?> data = (Map<?, ?>) rawData;
            if (!data.keySet().equals(Set.of("videoId", "ownerSubject", "title", "publishedAt", "skills", "hashtags"))) invalid();

            UUID eventId = uuid(root.get("eventId"));
            UUID videoId = uuid(data.get("videoId"));
            if (!videoId.toString().equals(kafkaKey)) throw new InvalidEvent("INVALID_MESSAGE_KEY");
            Instant occurredAt = timestamp(root.get("occurredAt"));
            Instant publishedAt = timestamp(data.get("publishedAt"));
            if (!occurredAt.equals(publishedAt)) invalid();
            String owner = text(data.get("ownerSubject"), MAX_EVENT_BYTES);
            Object rawTitle = data.get("title");
            if (!(rawTitle instanceof String)) invalid();
            String title = (String) rawTitle;
            if (title.isBlank() || title.length() > 150) invalid();
            List<FeedTag> skills = tags(data.get("skills"), 80);
            List<FeedTag> hashtags = tags(data.get("hashtags"), 50);
            return new PublishedVideo(eventId, videoId, owner, title, publishedAt, skills, hashtags, fingerprint(raw));
        } catch (InvalidEvent error) { throw error; }
        catch (RuntimeException error) { throw new InvalidEvent("INVALID_ENVELOPE"); }
    }

    private static List<FeedTag> tags(Object value, int maxNameLength) {
        if (!(value instanceof java.util.List<?>)) invalid();
        java.util.List<?> raw = (java.util.List<?>) value;
        if (raw.size() > 10) invalid();
        var result = new java.util.ArrayList<FeedTag>();
        var seen = new java.util.HashSet<UUID>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?>)) invalid();
            Map<?, ?> fields = (Map<?, ?>) item;
            if (!fields.keySet().equals(Set.of("id", "name"))) invalid();
            UUID id = uuid(fields.get("id"));
            String name = text(fields.get("name"), maxNameLength);
            if (!seen.add(id)) invalid();
            result.add(new FeedTag(id, name));
        }
        return List.copyOf(result);
    }

    private static String text(Object value, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                || text.chars().anyMatch(Character::isISOControl)) throw new InvalidEvent("INVALID_ENVELOPE");
        return text;
    }
    private static UUID uuid(Object value) {
        String raw = text(value, 36);
        UUID id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) invalid();
        return id;
    }
    private static Instant timestamp(Object value) {
        Instant instant = OffsetDateTime.parse(text(value, 80)).toInstant();
        int year = instant.atOffset(java.time.ZoneOffset.UTC).getYear();
        if (year < 1970 || year > 9999) invalid();
        return instant;
    }
    private static String fingerprint(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void invalid() { throw new InvalidEvent("INVALID_ENVELOPE"); }
}
