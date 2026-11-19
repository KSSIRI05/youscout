package ma.youscout.notification;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Un seul thread possède le consumer ; le commit PostgreSQL précède chaque commit d'offset (au moins une fois). */
@Component
@ConditionalOnProperty(name = "notification.ingestion.enabled", havingValue = "true")
class NotificationConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private final NotificationIngestion ingestion;
    private final String bootstrap, groupId;
    private final List<String> topics;
    private volatile boolean running;
    private volatile Consumer<String, String> consumer;
    private volatile Thread thread;

    NotificationConsumer(NotificationIngestion ingestion,
                         @Value("${notification.ingestion.bootstrap-servers}") String bootstrap,
                         @Value("${notification.ingestion.group-id}") String groupId,
                         @Value("${notification.ingestion.topics}") List<String> topics) {
        this.ingestion = ingestion;
        this.bootstrap = bootstrap;
        this.groupId = groupId;
        this.topics = topics;
    }

    private Consumer<String, String> createConsumer() {
        return new KafkaConsumer<>(Map.ofEntries(
            Map.entry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap),
            Map.entry(ConsumerConfig.GROUP_ID_CONFIG, groupId),
            Map.entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
            Map.entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
            Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Map.entry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false),
            Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50),
            Map.entry(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000),
            Map.entry(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000)));
    }

    @Override public synchronized void start() {
        if (running || (thread != null && thread.isAlive())) return;
        running = true;
        thread = Thread.ofPlatform().name("notification-ingestion").start(this::consume);
    }

    private void consume() {
        while (running) {
            Consumer<String, String> client = null;
            try {
                client = createConsumer();
                consumer = client;
                client.subscribe(topics);
                while (running) {
                    for (var record : client.poll(Duration.ofMillis(500))) {
                        if (!running) break;
                        ingestion.handle(record);
                        client.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5));
                    }
                }
            } catch (WakeupException stopped) {
                if (running) log.warn("Notification ingestion interrupted; pending events will be replayed.");
            } catch (RuntimeException unavailable) {
                if (running) log.warn("Notification ingestion unavailable; pending events will be replayed.");
            } finally {
                if (client != null) {
                    try { client.close(Duration.ofSeconds(3)); }
                    catch (RuntimeException ignored) { /* Reprise depuis les offsets déjà validés. */ }
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
            Consumer<String, String> client = consumer;
            if (client != null) client.wakeup();
        }
        if (current != null) {
            try { current.join(20000); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public boolean isRunning() { return running && thread != null && thread.isAlive(); }
}
