package ma.youscout.feed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Applies the ordered relationship projection before acknowledging each Kafka record. */
@Component
@ConditionalOnProperty(name = "feed.relations.enabled", havingValue = "true")
class UserRelationEventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(UserRelationEventConsumer.class);
    private final UserRelationEventHandler handler;
    private final RelationIngestionProperties properties;
    private volatile boolean running;
    private volatile Consumer<String, String> consumer;
    private volatile Thread thread;

    UserRelationEventConsumer(UserRelationEventHandler handler, RelationIngestionProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    private Consumer<String, String> createConsumer() {
        return new KafkaConsumer<>(Map.ofEntries(
            Map.entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers()),
            Map.entry(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId()),
            Map.entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Map.entry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false),
            Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20),
            Map.entry(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000),
            Map.entry(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000)));
    }

    @Override public synchronized void start() {
        if (running || (thread != null && thread.isAlive())) return;
        running = true;
        thread = Thread.ofPlatform().name("user-relationship-projection").start(this::consume);
    }

    private void consume() {
        while (running) {
            Consumer<String, String> client = null;
            try {
                client = createConsumer();
                consumer = client;
                client.subscribe(List.of(properties.topic()));
                while (running) {
                    for (var record : client.poll(Duration.ofMillis(500))) {
                        if (!running) break;
                        handler.handle(record);
                        client.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5));
                    }
                    handler.heartbeat();
                }
            } catch (WakeupException stopped) {
                if (running) log.warn("Relationship projection interrupted; events will be replayed.");
            } catch (RuntimeException unavailable) {
                if (running) log.warn("Relationship projection unavailable; events will be replayed.");
            } finally {
                if (client != null) {
                    try { client.close(Duration.ofSeconds(3)); }
                    catch (RuntimeException ignored) { /* Resume from the last committed event. */ }
                }
                consumer = null;
            }
            if (running) {
                try { Thread.sleep(1000); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @Override public void stop() {
        Thread current;
        synchronized (this) {
            running = false;
            current = thread;
            if (consumer != null) consumer.wakeup();
        }
        if (current != null) {
            try { current.join(20000); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public boolean isRunning() { return running && thread != null && thread.isAlive(); }
}
