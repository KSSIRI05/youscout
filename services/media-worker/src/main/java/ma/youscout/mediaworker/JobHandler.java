package ma.youscout.mediaworker;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
class JobHandler {
    private final EventDecoder decoder;
    private final JobStore jobs;
    JobHandler(EventDecoder decoder, JobStore jobs) { this.decoder = decoder; this.jobs = jobs; }

    void handle(ConsumerRecord<String, String> record) {
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
