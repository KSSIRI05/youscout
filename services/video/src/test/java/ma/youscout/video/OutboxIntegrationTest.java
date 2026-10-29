package ma.youscout.video;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL et Kafka réels ; aucune identité AWS n'est requise. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OutboxIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager manager;
    String topic;
    KafkaOutboxConfiguration.KafkaEventPublisher publisher;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("youscout.outbox.enabled", () -> "false");
    }
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM videos");
        topic = "video.uploaded.test." + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        publisher = new KafkaOutboxConfiguration().eventPublisher(kafka.getBootstrapServers(), topic);
    }
    @AfterEach void cleanup() { publisher.close(); }

    @Test void publishesConfirmedSnapshotWithVideoKeyAndMarksOnlyAfterAck() {
        UUID id = enqueue();
        assertThat(relay(publisher).publishNext()).isTrue();
        var records = consume(1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(id.toString());
        var payload = JsonPath.parse(records.getFirst().value());
        assertThat(payload.read("$.eventType", String.class)).isEqualTo("VideoUploaded");
        assertThat(payload.read("$.eventVersion", Integer.class)).isEqualTo(1);
        assertThat(payload.read("$.data.videoId", String.class)).isEqualTo(id.toString());
        assertThat(payload.read("$.data.sourceETag", String.class)).isEqualTo("\"opaque-etag\"");
        assertThat(payload.read("$.data.sizeBytes", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events", Boolean.class)).isTrue();
        assertThat(relay(publisher).publishNext()).isFalse();
    }

    @Test void realBrokerOutageRetainsEventAndAnotherRelayResumes() {
        enqueue();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThat(relay(publisher).publishNext()).isFalse();
            var row = jdbc.queryForMap("SELECT published_at, attempts, last_error, next_attempt_at > clock_timestamp() AS delayed FROM outbox_events");
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("last_error")).isEqualTo("KAFKA_UNAVAILABLE");
            assertThat(row.get("delayed")).isEqualTo(true);
            assertThat(relay(publisher).publishNext()).isFalse();
        } finally { docker.unpauseContainerCmd(kafka.getContainerId()).exec(); }
        dueNow();
        // Nouvelle instance : l'état et le payload viennent exclusivement de PostgreSQL.
        assertThat(relay(publisher).publishNext()).isTrue();
        assertThat(consume(1)).hasSize(1);
    }

    @Test void ackFollowedByLostConfirmationReplaysSameEventId() {
        enqueue();
        EventPublisher ambiguous = (id, payload) -> {
            publisher.publish(id, payload);
            throw new IllegalStateException("simulated crash after broker ACK");
        };
        assertThat(relay(ambiguous).publishNext()).isFalse();
        dueNow();
        assertThat(relay(publisher).publishNext()).isTrue();
        var records = consume(2);
        assertThat(records).hasSize(2);
        assertThat(records.stream().map(r -> JsonPath.parse(r.value()).read("$.eventId", String.class)).distinct().count()).isEqualTo(1);
        assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
    }

    @Test void twoRelaysSkipLockedEventRatherThanPublishingItTwice() throws Exception {
        enqueue();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> relay((id, payload) -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
            publisher.publish(id, payload);
        }).publishNext());
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(relay(publisher).publishNext()).isFalse();
        } finally { release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(consume(1)).hasSize(1);
    }

    @Test void enclosingDatabaseRollbackLeavesNoEvent() {
        UUID id = createVideo();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            outbox.enqueue(id);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test void uniquenessPreventsSecondEventForOneConfirmation() {
        UUID id = enqueue();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(id)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test void migrationBackfillsExistingConfirmedVideosOnlyOnce() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var before = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("2").load();
        before.migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".videos (id, owner_subject, title, s3_bucket, s3_key, content_type, size_bytes, idempotency_key, status, completed_at, source_etag) "
                + "VALUES (?, 'owner', 'Existing clip', 'test-bucket', ?, 'video/mp4', 12, ?, 'UPLOADED', statement_timestamp(), 'opaque')",
                id, "videos/" + id + "/source", UUID.randomUUID().toString());
        var after = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        after.migrate();
        after.migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".outbox_events WHERE aggregate_id = ?", Integer.class, id)).isEqualTo(1);
    }

    private OutboxRelay relay(EventPublisher target) { return new OutboxRelay(outbox, target, manager); }
    private void dueNow() { jdbc.update("UPDATE outbox_events SET next_attempt_at = clock_timestamp() - interval '1 second'"); }
    private UUID enqueue() {
        UUID id = createVideo();
        new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(id));
        return id;
    }
    private UUID createVideo() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO videos(id, owner_subject, title, s3_bucket, s3_key, content_type, size_bytes,
                idempotency_key, status, completed_at, source_etag)
            VALUES (?, 'test-owner', 'Clip', 'test-bucket', ?, 'video/mp4', 12, ?, 'UPLOADED', statement_timestamp(), '"opaque-etag"')
            """, id, "videos/" + id + "/source", UUID.randomUUID().toString());
        return id;
    }
    private List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> consume(int expected) {
        var result = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(result::add);
            }
        }
        return result;
    }
}
