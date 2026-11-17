package ma.youscout.feed;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
class UserRelationEventDecoder {
    static final String SNAPSHOT_COMPLETE = "UserRelationshipsSnapshotComplete";
    private static final int MAX_EVENT_BYTES = 8192;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    UserRelationEvent decode(String key, String raw) {
        if (!"youscout-user-relationships-v1".equals(key) || raw == null
                || raw.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) invalid();
        try {
            var root = json.readTree(raw);
            if (!root.isObject() || root.size() != 6 || !root.has("eventId") || !root.has("eventType")
                    || !root.has("eventVersion") || !root.has("occurredAt") || !root.has("producer")
                    || !root.has("data")) invalid();
            if (!"user-service".equals(root.path("producer").asText())
                    || root.path("eventVersion").asInt(-1) != 1) invalid();
            var id = java.util.UUID.fromString(root.path("eventId").asText());
            String type = root.path("eventType").asText();
            var occurredAt = java.time.Instant.parse(root.path("occurredAt").asText());
            var data = root.path("data");
            if (SNAPSHOT_COMPLETE.equals(type)) {
                if (!data.isObject() || data.size() != 0) invalid();
                return new UserRelationEvent(id, type, 1, occurredAt, "user-service", null);
            }
            if (!"UserRelationChanged".equals(type) || !data.isObject() || data.size() != 3
                    || !data.has("action") || !data.has("actorSubject") || !data.has("targetSubject")) invalid();
            String action = data.path("action").asText();
            if (!Set.of("FOLLOW", "UNFOLLOW", "BLOCK", "UNBLOCK").contains(action)) invalid();
            String actor = subject(data.path("actorSubject").asText());
            String target = subject(data.path("targetSubject").asText());
            if (actor.equals(target)) invalid();
            return new UserRelationEvent(id, type, 1, occurredAt, "user-service",
                    new UserRelationEvent.Data(action, actor, target));
        } catch (RuntimeException invalid) {
            if (invalid instanceof InvalidRelationshipEvent) throw invalid;
            throw new InvalidRelationshipEvent();
        }
    }

    private static String subject(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.indexOf('\0') >= 0) invalid();
        return value;
    }

    private static void invalid() { throw new InvalidRelationshipEvent(); }

    static final class InvalidRelationshipEvent extends RuntimeException {}
}
