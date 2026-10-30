package ma.youscout.mediaworker;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;

/** Outbox du résultat : le commit du job ne dépend jamais de Kafka. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "worker.processing.enabled", havingValue = "true")
class WorkerResultRelay implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaProducer<String, String> producer;
    WorkerResultRelay(JdbcTemplate jdbc, PlatformTransactionManager manager, IngestionProperties properties) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", properties.bootstrapServers(), "acks", "all",
            "enable.idempotence", true, "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class,
            "max.block.ms", 2000, "delivery.timeout.ms", 4000, "request.timeout.ms", 1500, "linger.ms", 0));
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    void poll() {
        try { publishNext(); }
        catch (RuntimeException unavailable) { LoggerFactory.getLogger(getClass()).warn("Result relay unavailable; pending events retained."); }
    }
    boolean publishNext() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var rows = jdbc.queryForList("""
                SELECT event_id, video_id, payload::text FROM worker_outbox WHERE published_at IS NULL
                AND next_attempt_at <= clock_timestamp() ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
            if (rows.isEmpty()) return false;
            var row = rows.getFirst(); UUID id = (UUID) row.get("event_id");
            try {
                producer.send(new ProducerRecord<>("video.processing.results.v1", row.get("video_id").toString(), (String) row.get("payload")))
                    .get(5, TimeUnit.SECONDS);
            } catch (Exception unavailable) {
                if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                jdbc.update("UPDATE worker_outbox SET attempts = attempts + 1, next_attempt_at = clock_timestamp() + interval '10 seconds' WHERE event_id = ?", id);
                return false;
            }
            jdbc.update("UPDATE worker_outbox SET published_at = clock_timestamp() WHERE event_id = ?", id);
            return true;
        }));
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() { producer.close(Duration.ofSeconds(3)); }
}
