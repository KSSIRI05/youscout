package ma.youscout.mediaworker;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Kafka et PostgreSQL réels ; aucun accès S3 ni données d'un compte réel. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "worker.ingestion.enabled=false")
@Testcontainers
class WorkerIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    @Autowired JdbcTemplate jdbc;
    @Autowired JobHandler handler;
    @Autowired JobStore jobs;
    String topic, group;
    Admin admin;
    KafkaProducer<String, String> producer;
    VideoIngestionWorker worker;
    final AtomicInteger clients = new AtomicInteger();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM rejected_messages"); jdbc.update("DELETE FROM processing_jobs");
        topic = "video.uploaded.test." + UUID.randomUUID(); group = "worker-test-" + UUID.randomUUID();
        admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
    }
    @AfterEach void cleanup() {
        if (worker != null) worker.stop();
        producer.close(Duration.ofSeconds(3)); admin.close(Duration.ofSeconds(3));
    }

    @Test void duplicateDeliveryCreatesOneQueuedJobWithoutChangingDates() throws Exception {
        start(); UUID id = UUID.randomUUID(), video = UUID.randomUUID();
        var event = EventFixture.event(id, video);
        send(video, EventFixture.json(event)); awaitOffset(1);
        var before = jdbc.queryForMap("SELECT * FROM processing_jobs WHERE event_id = ?", id);
        send(video, EventFixture.json(event)); awaitOffset(2);
        assertThat(jdbc.queryForMap("SELECT * FROM processing_jobs WHERE event_id = ?", id)).isEqualTo(before);
        assertThat(before.get("status")).isEqualTo("QUEUED");
        assertThat(before.get("source_etag")).isEqualTo("\"opaque-etag\"");
        assertThat(count()).isEqualTo(1);
    }

    @Test void restartAndOffsetReplayDoNotCreateAnotherJob() throws Exception {
        start(); UUID id = UUID.randomUUID(), video = UUID.randomUUID();
        send(video, EventFixture.json(EventFixture.event(id, video))); awaitOffset(1);
        var created = jdbc.queryForObject("SELECT created_at FROM processing_jobs WHERE event_id = ?", java.sql.Timestamp.class, id);
        worker.stop();
        admin.alterConsumerGroupOffsets(group, Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(0))).all().get(10, TimeUnit.SECONDS);
        start(); awaitOffset(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM processing_jobs WHERE event_id = ?", java.sql.Timestamp.class, id)).isEqualTo(created);
        assertThat(count()).isEqualTo(1);
        UUID nextVideo = UUID.randomUUID();
        send(nextVideo, EventFixture.json(EventFixture.event(UUID.randomUUID(), nextVideo))); awaitOffset(2);
        assertThat(count()).isEqualTo(2);
    }

    @Test void databaseFailureDoesNotAcknowledgeKafkaAndRecoveryReplays() throws Exception {
        jdbc.execute("ALTER TABLE processing_jobs ADD CONSTRAINT test_reject CHECK (false)");
        try {
            start(); UUID video = UUID.randomUUID();
            send(video, EventFixture.json(EventFixture.event(UUID.randomUUID(), video)));
            await(() -> clients.get() >= 2);
            assertThat(committed()).isZero(); assertThat(count()).isZero();
        } finally { jdbc.execute("ALTER TABLE processing_jobs DROP CONSTRAINT test_reject"); }
        awaitOffset(1); assertThat(count()).isEqualTo(1);
    }

    @Test void malformedMessageIsQuarantinedBeforeFollowingValidMessage() throws Exception {
        start();
        send(UUID.randomUUID(), "{\"accessToken\":\"fixture-must-not-be-stored\"}");
        UUID video = UUID.randomUUID(); send(video, EventFixture.json(EventFixture.event(UUID.randomUUID(), video)));
        awaitOffset(2);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM rejected_messages", String.class)).isEqualTo("INVALID_ENVELOPE");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'rejected_messages'", String.class))
                .containsExactlyInAnyOrder("topic", "partition_id", "record_offset", "reason", "created_at");
    }

    @Test void conflictingEventOrVideoNeverOverwritesExistingJob() throws Exception {
        start(); UUID id = UUID.randomUUID(), video = UUID.randomUUID();
        var event = EventFixture.event(id, video); send(video, EventFixture.json(event)); awaitOffset(1);
        EventFixture.data(event).put("sourceETag", "different-etag"); send(video, EventFixture.json(event));
        send(video, EventFixture.json(EventFixture.event(UUID.randomUUID(), video))); awaitOffset(3);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_etag FROM processing_jobs", String.class)).isEqualTo("\"opaque-etag\"");
        assertThat(jdbc.queryForList("SELECT reason FROM rejected_messages", String.class))
                .containsExactlyInAnyOrder("EVENT_ID_CONFLICT", "VIDEO_ALREADY_QUEUED");
    }

    @Test void concurrentDuplicateInsertsAreDeduplicatedByDatabase() {
        UUID video = UUID.randomUUID();
        var event = new EventDecoder().decode(video.toString(), EventFixture.json(EventFixture.event(UUID.randomUUID(), video)));
        var futures = java.util.stream.IntStream.range(0, 5).mapToObj(n -> CompletableFuture.runAsync(() -> jobs.accept(event, topic, 0, n))).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rejected_messages", Integer.class)).isZero();
    }

    private void start() {
        var properties = new IngestionProperties(true, kafka.getBootstrapServers(), topic, group);
        worker = new VideoIngestionWorker(handler, properties, () -> {
            clients.incrementAndGet(); return VideoIngestionWorker.createConsumer(properties);
        });
        worker.start();
    }
    private void send(UUID videoId, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, 0, videoId.toString(), value)).get(10, TimeUnit.SECONDS);
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM processing_jobs", Integer.class); }
    private long committed() throws Exception {
        var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(topic, 0));
        return offset == null ? 0 : offset.offset();
    }
    private void awaitOffset(long expected) throws Exception { await(() -> committed() >= expected); }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(100);
        }
        fail("Worker did not reach the expected durable state.");
    }
}
