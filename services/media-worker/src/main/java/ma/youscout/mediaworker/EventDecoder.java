package ma.youscout.mediaworker;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Valide le contrat VideoUploaded v1 avant toute insertion métier. */
@Component
class EventDecoder {
    static class InvalidEvent extends RuntimeException {
        InvalidEvent(String code) { super(code); }
    }
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(65536).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    VideoUploaded decode(String kafkaKey, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw new InvalidEvent("INVALID_ENVELOPE");
        try {
            Map<?, ?> root = mapper.readValue(raw, Map.class);
            if (root == null || !root.keySet().equals(Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data"))) invalid();
            if (!"VideoUploaded".equals(root.get("eventType")) || !"video-service".equals(root.get("producer"))) invalid();
            if (!(root.get("eventVersion") instanceof Integer version) || version != 1) invalid();
            if (!(root.get("data") instanceof Map<?, ?> data) || !data.keySet().equals(
                    Set.of("videoId", "s3Bucket", "s3Key", "sourceETag", "contentType", "sizeBytes"))) invalid();
            Map<?, ?> data = (Map<?, ?>) root.get("data");
            UUID eventId = uuid(root.get("eventId")), videoId = uuid(data.get("videoId"));
            if (!videoId.toString().equals(kafkaKey)) throw new InvalidEvent("INVALID_MESSAGE_KEY");
            var date = OffsetDateTime.parse(text(root.get("occurredAt"), 80));
            if (date.getYear() < 1970 || date.getYear() > 9999) invalid();
            String bucket = text(data.get("s3Bucket"), 63), key = text(data.get("s3Key"), 100);
            if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") || !key.equals("videos/" + videoId + "/source")) invalid();
            String etag = text(data.get("sourceETag"), 1024), type = text(data.get("contentType"), 40);
            if (!Set.of("video/mp4", "video/webm", "video/quicktime").contains(type)) invalid();
            Object size = data.get("sizeBytes");
            if (!(size instanceof Integer || size instanceof Long)) invalid();
            long bytes = ((Number) size).longValue();
            if (bytes < 1 || bytes > 5368709120L) invalid();
            return new VideoUploaded(eventId, videoId, date.toInstant(), bucket, key, etag, type, bytes);
        } catch (InvalidEvent error) { throw error; }
        catch (RuntimeException error) { throw new InvalidEvent("INVALID_ENVELOPE"); }
    }
    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max
                || text.chars().anyMatch(Character::isISOControl)) throw new InvalidEvent("INVALID_ENVELOPE");
        return (String) value;
    }
    private static UUID uuid(Object value) {
        String text = text(value, 36);
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) invalid();
        return id;
    }
    private static void invalid() { throw new InvalidEvent("INVALID_ENVELOPE"); }
}
