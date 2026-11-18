package ma.youscout.feed;

import static ma.youscout.feed.PublishedVideoDecoder.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

record ModeratedVideo(UUID eventId, UUID videoId, String ownerSubject, boolean removed, long version) {}

/** Valide le contrat VideoModerated v1 avec les mêmes règles strictes que les autres événements Video. */
@Component
class ModeratedVideoDecoder {
    ModeratedVideo decode(String kafkaKey, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) invalid();
        try {
            Map<?, ?> root = MAPPER.readValue(raw, Map.class);
            if (root == null || !root.keySet().equals(Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data"))) invalid();
            if (!"VideoModerated".equals(root.get("eventType")) || !"video-service".equals(root.get("producer"))) invalid();
            if (!(root.get("eventVersion") instanceof Integer eventVersion) || eventVersion != 1) invalid();
            if (!(root.get("data") instanceof Map<?, ?> data)
                    || !data.keySet().equals(Set.of("videoId", "ownerSubject", "action", "version", "moderatedAt"))) {
                invalid();
                return null;
            }
            UUID eventId = uuid(root.get("eventId"));
            UUID videoId = uuid(data.get("videoId"));
            if (!videoId.toString().equals(kafkaKey)) throw new InvalidEvent("INVALID_MESSAGE_KEY");
            Instant moderatedAt = timestamp(data.get("moderatedAt"));
            if (!timestamp(root.get("occurredAt")).equals(moderatedAt)) invalid();
            if (!(data.get("action") instanceof String action) || !Set.of("REMOVED", "ACTIVE").contains(action)) {
                invalid();
                return null;
            }
            if (!(data.get("version") instanceof Number version) || version instanceof Double || version.longValue() < 1) {
                invalid();
                return null;
            }
            return new ModeratedVideo(eventId, videoId, text(data.get("ownerSubject"), MAX_EVENT_BYTES), action.equals("REMOVED"), version.longValue());
        } catch (InvalidEvent error) { throw error; }
        catch (RuntimeException error) { throw new InvalidEvent("INVALID_ENVELOPE"); }
    }
}
