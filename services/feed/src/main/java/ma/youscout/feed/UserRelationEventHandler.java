package ma.youscout.feed;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
class UserRelationEventHandler {
    private final UserRelationEventDecoder decoder;
    private final FeedRelationsProjection projection;

    UserRelationEventHandler(UserRelationEventDecoder decoder, FeedRelationsProjection projection) {
        this.decoder = decoder;
        this.projection = projection;
    }

    void handle(ConsumerRecord<String, String> record) {
        var event = decoder.decode(record.key(), record.value());
        if (UserRelationEventDecoder.SNAPSHOT_COMPLETE.equals(event.eventType())) {
            projection.markSnapshotComplete();
        } else {
            projection.apply(event.data());
        }
    }

    void heartbeat() { projection.heartbeat(); }
}
