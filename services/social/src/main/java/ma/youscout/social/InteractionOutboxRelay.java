package ma.youscout.social;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publie les lignes d'outbox validées au moins une fois ; les consommateurs dédupliquent par eventId. */
@Component
@ConditionalOnProperty(name = "social.outbox.enabled", havingValue = "true")
class InteractionOutboxRelay implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InteractionOutboxRelay.class);
    private record Pending(UUID id, String key, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KafkaProducer<String, String> producer;
    private final String topic;

    InteractionOutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager manager,
                           @Value("${social.outbox.bootstrap-servers}") String bootstrap,
                           @Value("${social.outbox.topic}") String topic) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.topic = topic;
        this.producer = new KafkaProducer<>(Map.ofEntries(
            Map.entry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap),
            Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
            Map.entry(ProducerConfig.CLIENT_ID_CONFIG, "youscout-social-outbox"),
            Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
            Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
            Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500),
            Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4000),
            Map.entry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000),
            Map.entry(ProducerConfig.LINGER_MS_CONFIG, 0)));
    }

    @Scheduled(fixedDelayString = "${social.outbox.poll-ms:1000}", initialDelayString = "3000")
    void poll() {
        try {
            for (int count = 0; count < 20 && publishNext(); count++) { /* lot borné */ }
        } catch (RuntimeException unavailable) {
            log.warn("Social outbox unavailable; pending events will be retried.");
        }
    }

    boolean publishNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<Pending> next = jdbc.query("""
                SELECT event_id, message_key, payload::text FROM social_event_outbox
                 WHERE published_at IS NULL ORDER BY event_sequence LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (r, n) -> new Pending(r.getObject(1, UUID.class), r.getString(2), r.getString(3)));
            if (next.isEmpty()) return false;
            Pending event = next.getFirst();
            try {
                producer.send(new ProducerRecord<>(topic, event.key(), event.payload())).get(5, TimeUnit.SECONDS);
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                jdbc.update("UPDATE social_event_outbox SET attempts = attempts + 1 WHERE event_id = ?", event.id());
                log.warn("Kafka unavailable; social event {} retained for retry.", event.id());
                return false;
            }
            jdbc.update("UPDATE social_event_outbox SET published_at = clock_timestamp() WHERE event_id = ?", event.id());
            return true;
        }));
    }

    @Override public void close() { producer.close(Duration.ofSeconds(3)); }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "social.outbox.enabled", havingValue = "true")
    static class Scheduling {}
}
