package ma.youscout.mediaworker;

import static ma.youscout.mediaworker.EventDecoder.*;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

record VideoDeleted(UUID eventId, UUID videoId) {}

/** Valide le contrat VideoDeleted v1 ; seul videoId sert au nettoyage, les clés S3 sont recalculées localement. */
@Component
class DeletionDecoder {
    VideoDeleted decode(String kafkaKey, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw new InvalidEvent("INVALID_ENVELOPE");
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
            UUID eventId = uuid(root.get("eventId")), videoId = uuid(data.get("videoId"));
            if (!videoId.toString().equals(kafkaKey)) throw new InvalidEvent("INVALID_MESSAGE_KEY");
            text(data.get("ownerSubject"), 65536);
            var deletedAt = OffsetDateTime.parse(text(data.get("deletedAt"), 80));
            if (!deletedAt.isEqual(OffsetDateTime.parse(text(root.get("occurredAt"), 80)))) invalid();
            if (deletedAt.getYear() < 1970 || deletedAt.getYear() > 9999) invalid();
            if (!(data.get("wasPublished") instanceof Boolean)) invalid();
            return new VideoDeleted(eventId, videoId);
        } catch (InvalidEvent error) { throw error; }
        catch (RuntimeException error) { throw new InvalidEvent("INVALID_ENVELOPE"); }
    }
}
