package ma.youscout.feed;

import static ma.youscout.feed.PublishedVideoDecoder.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

record DeletedVideo(UUID eventId, UUID videoId, String ownerSubject, Instant deletedAt, String fingerprint) {}

/** Valide le contrat VideoDeleted v1 avec les mêmes règles strictes que VideoPublished. */
@Component
class DeletedVideoDecoder {
    DeletedVideo decode(String kafkaKey, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) invalid();
        try {
            Map<?, ?> root = MAPPER.readValue(raw, Map.class);
            if (root == null || !root.keySet().equals(Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data"))) invalid();
            if (!"VideoDeleted".equals(root.get("eventType")) || !"video-service".equals(root.get("producer"))) invalid();
            if (!(root.get("eventVersion") instanceof Integer version) || version != 1) invalid();
            if (!(root.get("data") instanceof Map<?, ?> data)
                    || !data.keySet().equals(Set.of("videoId", "ownerSubject", "deletedAt", "wasPublished"))) {
                invalid();
                return null;
            }
            UUID eventId = uuid(root.get("eventId"));
            UUID videoId = uuid(data.get("videoId"));
            if (!videoId.toString().equals(kafkaKey)) throw new InvalidEvent("INVALID_MESSAGE_KEY");
            Instant deletedAt = timestamp(data.get("deletedAt"));
            if (!timestamp(root.get("occurredAt")).equals(deletedAt)) invalid();
            if (!(data.get("wasPublished") instanceof Boolean)) invalid();
            return new DeletedVideo(eventId, videoId, text(data.get("ownerSubject"), MAX_EVENT_BYTES), deletedAt, fingerprint(raw));
        } catch (InvalidEvent error) { throw error; }
        catch (RuntimeException error) { throw new InvalidEvent("INVALID_ENVELOPE"); }
    }
}
