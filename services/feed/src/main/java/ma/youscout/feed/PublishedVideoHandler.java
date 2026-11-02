package ma.youscout.feed;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/** Valide et persiste avant que le consommateur puisse acquitter l'offset Kafka. */
@Component
class PublishedVideoHandler {
    private final PublishedVideoDecoder decoder;
    private final FeedStore store;
    private final FeedRelationsProjection projection;
    PublishedVideoHandler(PublishedVideoDecoder decoder, FeedStore store, FeedRelationsProjection projection) {
        this.decoder = decoder;
        this.store = store;
        this.projection = projection;
    }

    void handle(ConsumerRecord<String, String> record) {
        try {
            store.accept(decoder.decode(record.key(), record.value()), record.topic(), record.partition(), record.offset());
            projection.videoPublished();
        } catch (PublishedVideoDecoder.InvalidEvent invalid) {
            store.reject(record.topic(), record.partition(), record.offset(), invalid.getMessage());
        }
    }
}
