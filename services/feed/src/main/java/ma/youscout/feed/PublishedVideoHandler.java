package ma.youscout.feed;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** Valide et persiste avant que le consommateur puisse acquitter l'offset Kafka. */
@Component
class PublishedVideoHandler {
    private final PublishedVideoDecoder decoder;
    private final DeletedVideoDecoder deletions;
    private final FeedStore store;
    private final FeedRelationsProjection projection;
    private final FeedIngestionProperties properties;
    PublishedVideoHandler(PublishedVideoDecoder decoder, DeletedVideoDecoder deletions, FeedStore store,
                          FeedRelationsProjection projection, FeedIngestionProperties properties) {
        this.decoder = decoder;
        this.deletions = deletions;
        this.store = store;
        this.projection = projection;
        this.properties = properties;
    }

    void handle(ConsumerRecord<String, String> record) {
        try {
            boolean changed = properties.deletedTopic().equals(record.topic())
                ? store.acceptDeletion(deletions.decode(record.key(), record.value()), record.topic(), record.partition(), record.offset())
                : store.accept(decoder.decode(record.key(), record.value()), record.topic(), record.partition(), record.offset());
            // Invalidates every cached page only when the visible feed actually changed.
            if (changed) projection.videosChanged();
        } catch (PublishedVideoDecoder.InvalidEvent invalid) {
            store.reject(record.topic(), record.partition(), record.offset(), invalid.getMessage());
        }
    }
}
