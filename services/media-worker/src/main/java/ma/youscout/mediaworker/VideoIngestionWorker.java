package ma.youscout.mediaworker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Un seul thread possède le consumer ; wakeup() est le seul appel depuis le thread d'arrêt. */
@Component
@ConditionalOnProperty(name = "worker.ingestion.enabled", havingValue = "true")
class VideoIngestionWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(VideoIngestionWorker.class);
    private final JobHandler handler;
    private final IngestionProperties properties;
    private final Supplier<Consumer<String, String>> factory;
    private volatile boolean running;
    private volatile Consumer<String, String> consumer;
    private volatile Thread thread;

    @Autowired VideoIngestionWorker(JobHandler handler, IngestionProperties properties) {
        this(handler, properties, () -> createConsumer(properties));
    }
    VideoIngestionWorker(JobHandler handler, IngestionProperties properties, Supplier<Consumer<String, String>> factory) {
        this.handler = handler; this.properties = properties; this.factory = factory;
    }
    static Consumer<String, String> createConsumer(IngestionProperties properties) {
        return new KafkaConsumer<>(Map.ofEntries(
            Map.entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers()),
            Map.entry(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId()),
            Map.entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Map.entry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false),
            Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10),
            Map.entry(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000),
            Map.entry(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000)));
    }
    @Override public synchronized void start() {
        if (running || (thread != null && thread.isAlive())) return;
        running = true;
        thread = Thread.ofPlatform().name("video-ingestion").start(this::consume);
    }
    private void consume() {
        while (running) {
            Consumer<String, String> client = null;
            try {
                client = factory.get();
                consumer = client;
                client.subscribe(List.of(properties.topic()));
                while (running) {
                    var records = client.poll(Duration.ofMillis(500));
                    for (var record : records) {
                        if (!running) break;
                        handler.handle(record); // Retour uniquement après le commit PostgreSQL.
                        client.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5));
                    }
                }
            } catch (WakeupException wakeup) {
                if (running) log.warn("Ingestion interrupted; unacknowledged events will be replayed.");
            } catch (RuntimeException unavailable) {
                // Pas de payload, de mot de passe ou de message du driver dans les diagnostics.
                if (running) log.warn("Ingestion unavailable; unacknowledged events will be replayed.");
            } finally {
                if (client != null) {
                    try { client.close(Duration.ofSeconds(3)); }
                    catch (RuntimeException ignored) { /* Reprise avec les offsets déjà acquittés seulement. */ }
                }
                consumer = null;
            }
            if (running) {
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    @Override public void stop() {
        Thread current;
        synchronized (this) {
            running = false;
            current = thread;
            var client = consumer;
            if (client != null) client.wakeup();
        }
        if (current != null) {
            try { current.join(20000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    @Override public boolean isRunning() { return running && thread != null && thread.isAlive(); }
}
