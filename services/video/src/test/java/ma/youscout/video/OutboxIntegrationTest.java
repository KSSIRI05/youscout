package ma.youscout.video;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.*;

/** MongoDB et Kafka réels ; aucune identité AWS n'est requise. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OutboxIntegrationTest {
    @Container static final MongoDBContainer mongoDb = new MongoDBContainer("mongo:7").withReplicaSet();
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    @Autowired MongoTemplate mongo;
    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager manager;
    String topic;
    KafkaOutboxConfiguration.KafkaEventPublisher publisher;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongoDb.getReplicaSetUrl("youscout_video"));
        registry.add("youscout.outbox.enabled", () -> "false");
    }
    @BeforeEach void setup() throws Exception {
        mongo.remove(new Query(), "video_outbox");
        mongo.remove(new Query(), "videos");
        topic = "video.uploaded.test." + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        publisher = new KafkaOutboxConfiguration().eventPublisher(kafka.getBootstrapServers(), topic, topic);
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
        assertThat(mongo.findOne(new Query(), Document.class, "video_outbox").get("publishedAt")).isNotNull();
        assertThat(relay(publisher).publishNext()).isFalse();
    }

    @Test void realBrokerOutageRetainsEventAndAnotherRelayResumes() {
        enqueue();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThat(relay(publisher).publishNext()).isFalse();
            var row = mongo.findOne(new Query(), Document.class, "video_outbox");
            assertThat(row.get("publishedAt")).isNull();
            assertThat(row.getInteger("attempts")).isEqualTo(1);
            assertThat(row.getString("lastError")).isEqualTo("KAFKA_UNAVAILABLE");
            assertThat(row.getDate("nextAttemptAt")).isAfter(new Date());
            assertThat(relay(publisher).publishNext()).isFalse();
        } finally { docker.unpauseContainerCmd(kafka.getContainerId()).exec(); }
        dueNow();
        // Nouvelle instance : l'état et le payload viennent exclusivement de MongoDB.
        assertThat(relay(publisher).publishNext()).isTrue();
        assertThat(consume(1)).hasSize(1);
    }

    @Test void ackFollowedByLostConfirmationReplaysSameEventId() {
        enqueue();
        EventPublisher ambiguous = (id, eventType, payload) -> {
            publisher.publish(id, eventType, payload);
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
        var first = CompletableFuture.supplyAsync(() -> relay((id, eventType, payload) -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException();
            publisher.publish(id, eventType, payload);
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
        assertThat(mongo.count(new Query(), "video_outbox")).isZero();
    }

    @Test void uniquenessPreventsSecondEventForOneConfirmation() {
        UUID id = enqueue();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(id)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(mongo.count(new Query(), "video_outbox")).isEqualTo(1);
    }

    @Test void mongoIndexesAreInstalledAndEnforceEventDeduplication() {
        assertThat(mongo.indexOps("video_outbox").getIndexInfo()).extracting("name")
                .contains("video_outbox_aggregate_type_unique", "video_outbox_pending");
        UUID id = enqueue();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(id)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(mongo.count(Query.query(Criteria.where("aggregateId").is(id.toString())), "video_outbox")).isEqualTo(1);
    }

    private OutboxRelay relay(EventPublisher target) { return new OutboxRelay(outbox, target, manager); }
    private void dueNow() { mongo.updateMulti(new Query(), new Update().set("nextAttemptAt", new Date(System.currentTimeMillis() - 1000)), "video_outbox"); }
    private UUID enqueue() {
        UUID id = createVideo();
        new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(id));
        return id;
    }
    private UUID createVideo() {
        UUID id = UUID.randomUUID();
        mongo.insert(new Document("_id", id.toString()).append("id", id.toString()).append("ownerSubject", "test-owner")
                .append("title", "Clip").append("s3Bucket", "test-bucket").append("s3Key", "videos/" + id + "/source")
                .append("contentType", "video/mp4").append("sizeBytes", 12L)
                .append("idempotencyKey", UUID.randomUUID().toString()).append("status", "UPLOADED")
                .append("completedAt", new Date()).append("sourceETag", "\"opaque-etag\"")
                .append("publicationStatus", "UNPUBLISHED"), "videos");
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
