package ma.youscout.feed;

import java.time.Instant;
import java.util.UUID;

record UserRelationEvent(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                         String producer, Data data) {
    record Data(String action, String actorSubject, String targetSubject) {}
}
