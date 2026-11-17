package ma.youscout.user;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

class RelationKafkaPublisher implements AutoCloseable {
    private final KafkaProducer<String, String> producer;
    private final String topic;

    RelationKafkaPublisher(KafkaProducer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    void publish(String key, String payload) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, payload)).get(5, TimeUnit.SECONDS);
    }

    @Override public void close() {
        producer.close(Duration.ofSeconds(3));
    }
}
