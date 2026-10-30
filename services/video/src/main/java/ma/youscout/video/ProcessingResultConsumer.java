package ma.youscout.video;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "youscout.processing-results.enabled", havingValue = "true")
class ProcessingResultConsumer implements SmartLifecycle {
    private final ProcessingResultStore store;
    private final String bootstrap;
    private final String topic;
    private final String group;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    ProcessingResultConsumer(ProcessingResultStore store, @Value("${youscout.outbox.bootstrap-servers}") String bootstrap,
            @Value("${youscout.processing-results.topic:video.processing.results.v1}") String topic,
            @Value("${youscout.processing-results.group:youscout-video-results-v1}") String group) {
        this.store = store; this.bootstrap = bootstrap; this.topic = topic; this.group = group;
    }
    @Override public synchronized void start() {
        if (running || (thread != null && thread.isAlive())) return;
        running = true; thread = Thread.ofPlatform().name("video-processing-results").start(this::consume);
    }
    void consume() {
        while (running) {
            KafkaConsumer<String, String> client = null;
            try {
                client = new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap, "group.id", group, "enable.auto.commit", false,
                    "auto.offset.reset", "earliest", "allow.auto.create.topics", false, "max.poll.records", 10,
                    "default.api.timeout.ms", 5000, "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
                consumer = client; client.subscribe(List.of(topic));
                while (running) {
                    for (var record : client.poll(Duration.ofMillis(500))) {
                        if (!running) break;
                        ProcessingResult result = null;
                        try { result = ProcessingResult.decode(record.key(), record.value()); }
                        catch (RuntimeException invalid) { store.reject(record.topic(), record.partition(), record.offset(), "INVALID_RESULT"); }
                        if (result != null) store.accept(result, record.topic(), record.partition(), record.offset());
                        client.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5));
                    }
                }
            } catch (WakeupException stopped) { /* Les offsets non acquittés restent rejouables. */ }
            catch (RuntimeException unavailable) { if (running) LoggerFactory.getLogger(getClass()).warn("Result ingestion unavailable; events retained in Kafka."); }
            finally {
                if (client != null) { try { client.close(Duration.ofSeconds(3)); } catch (RuntimeException ignored) {} }
                consumer = null;
            }
            if (running) { try { Thread.sleep(1000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; } }
        }
    }
    @Override public void stop() {
        running = false; var client = consumer; if (client != null) client.wakeup();
        var current = thread; if (current != null) { try { current.join(20000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } }
    }
    @Override public boolean isRunning() { return running && thread != null && thread.isAlive(); }
}
