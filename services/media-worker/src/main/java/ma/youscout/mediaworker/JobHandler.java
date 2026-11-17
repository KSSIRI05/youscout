package ma.youscout.mediaworker;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
class JobHandler {
    private final EventDecoder decoder;
    private final JobStore jobs;
    private final DeletionDecoder deletionDecoder;
    private final DeletionStore deletions;
    private final IngestionProperties properties;
    JobHandler(EventDecoder decoder, JobStore jobs, DeletionDecoder deletionDecoder, DeletionStore deletions,
               IngestionProperties properties) {
        this.decoder = decoder; this.jobs = jobs; this.deletionDecoder = deletionDecoder; this.deletions = deletions;
        this.properties = properties;
    }

    void handle(ConsumerRecord<String, String> record) {
        if (properties.deletedTopic().equals(record.topic())) {
            try { deletions.accept(deletionDecoder.decode(record.key(), record.value())); }
            catch (EventDecoder.InvalidEvent invalid) {
                jobs.reject(record.topic(), record.partition(), record.offset(), invalid.getMessage());
            }
            return;
        }
        VideoUploaded event;
        try { event = decoder.decode(record.key(), record.value()); }
        catch (EventDecoder.InvalidEvent invalid) {
            // La quarantaine est persistée avant l'ACK : un message malformé ne bloque pas la partition.
            jobs.reject(record.topic(), record.partition(), record.offset(), invalid.getMessage());
            return;
        }
        jobs.accept(event, record.topic(), record.partition(), record.offset());
    }
}
