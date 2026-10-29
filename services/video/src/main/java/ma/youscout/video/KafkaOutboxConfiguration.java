package ma.youscout.video;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Configuration locale sans secret ; TLS/SASL et ACL seront requis pour un déploiement distribué. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "youscout.outbox.enabled", havingValue = "true")
class KafkaOutboxConfiguration {
    @Bean(destroyMethod = "close")
    KafkaEventPublisher eventPublisher(
            @Value("${youscout.outbox.bootstrap-servers}") String bootstrap,
            @Value("${youscout.outbox.topic}") String topic) {
        var producer = new KafkaProducer<String, String>(Map.ofEntries(
            Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap),
            Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "youscout-video-outbox"),
            Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
            Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500),
            Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000),
            Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000),
            Map.entry(ProducerConfig.LINGER_MS_CONFIG, 0)));
        return new KafkaEventPublisher(producer, topic);
    }

    static class KafkaEventPublisher implements EventPublisher, AutoCloseable {
        private final KafkaProducer<String, String> producer;
        private final String topic;
        KafkaEventPublisher(KafkaProducer<String, String> producer, String topic) {
            this.producer = producer; this.topic = topic;
        }
        @Override public void publish(UUID videoId, String payload) throws Exception {
            // Clé stable pour le partitionnement ; l'eventId du JSON déduplique les reprises après crash.
            producer.send(new ProducerRecord<>(topic, videoId.toString(), payload)).get(5, TimeUnit.SECONDS);
        }
        @Override public void close() { producer.close(Duration.ofSeconds(3)); }
    }
}
