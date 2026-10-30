package ma.youscout.mediaworker;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;

/** FFmpeg, SDK S3, PostgreSQL et Kafka réels ; seul le serveur S3 est simulé et isolé. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "worker.ingestion.enabled=false")
@Testcontainers
class ProcessingPipelineIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionStore store;
    @Autowired JobStore jobs;
    @Autowired PlatformTransactionManager manager;
    @TempDir Path directory;
    HttpServer server;
    S3Client s3;
    byte[] source;
    int sourceStatus = 200;
    final AtomicReference<String> ifMatch = new AtomicReference<>();
    final Map<String, Integer> uploads = new ConcurrentHashMap<>();
    UUID eventId, videoId;
    ProcessingCoordinator coordinator;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM processing_jobs");
        FfmpegProcessorTest.clip(directory); source = Files.readAllBytes(directory.resolve("source"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
                byte[] body = sourceStatus == 200 ? source : "<Error><Code>PreconditionFailed</Code></Error>".getBytes();
                exchange.sendResponseHeaders(sourceStatus, body.length); exchange.getResponseBody().write(body);
            } else {
                uploads.put(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes().length);
                exchange.getResponseHeaders().add("ETag", "\"derived-etag\""); exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        }); server.start();
        s3 = S3Client.builder().region(Region.US_EAST_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("fake-test", "fake-test")))
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        coordinator = new ProcessingCoordinator(store, new S3MediaStorage(s3, FfmpegProcessorTest.properties(180)), FfmpegProcessorTest.properties(180));
        eventId = UUID.randomUUID(); videoId = UUID.randomUUID();
        var event = EventFixture.event(eventId, videoId); EventFixture.data(event).put("sizeBytes", source.length);
        jobs.accept(new EventDecoder().decode(videoId.toString(), EventFixture.json(event)), "test", 0, 0);
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            try { admin.createTopics(List.of(new NewTopic("video.processing.results.v1", 1, (short) 1))).all().get(10, TimeUnit.SECONDS); }
            catch (ExecutionException existing) { if (!(existing.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw existing; }
        }
    }
    @AfterEach void close() { s3.close(); server.stop(0); }

    @Test void sourceToDerivedFilesAndDurableKafkaResult() {
        assertThat(coordinator.runOne()).isTrue();
        assertThat(ifMatch.get()).isEqualTo("\"opaque-etag\"");
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("SUCCEEDED");
        String videoKey = jdbc.queryForObject("SELECT output_video_key FROM processing_jobs", String.class);
        String thumbnail = jdbc.queryForObject("SELECT thumbnail_key FROM processing_jobs", String.class);
        assertThat(uploads).containsKeys("/test-bucket/" + videoKey, "/test-bucket/" + thumbnail);
        assertThat(uploads.values()).allMatch(size -> size > 0);
        assertThat(coordinator.runOne()).isFalse();
        try (var relay = relay()) { assertThat(relay.publishNext()).isTrue(); assertThat(relay.publishNext()).isFalse(); }
        var event = readOwnResult();
        assertThat(event.get("eventType")).isEqualTo("VideoProcessed");
        assertThat(((Map<?, ?>) event.get("data")).get("videoKey")).isEqualTo(videoKey);
    }
    @Test void changedSourceProducesFailureWithNoDerivedUploads() {
        sourceStatus = 412; coordinator.runOne();
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM processing_jobs", String.class)).isEqualTo("SOURCE_CHANGED");
        assertThat(uploads).isEmpty();
        try (var relay = relay()) { assertThat(relay.publishNext()).isTrue(); }
        assertThat(readOwnResult().get("eventType")).isEqualTo("VideoProcessingFailed");
    }
    @Test void resultOutboxSurvivesBrokerOutageWithStableEventId() {
        coordinator.runOne();
        UUID resultId = jdbc.queryForObject("SELECT event_id FROM worker_outbox", UUID.class);
        var docker = DockerClientFactory.instance().client();
        try (var relay = relay()) {
            docker.pauseContainerCmd(kafka.getContainerId()).exec();
            try {
                assertThat(relay.publishNext()).isFalse();
                assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM worker_outbox", Boolean.class)).isTrue();
            } finally { docker.unpauseContainerCmd(kafka.getContainerId()).exec(); }
            jdbc.update("UPDATE worker_outbox SET next_attempt_at = clock_timestamp() - interval '1 second'");
            assertThat(relay.publishNext()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT event_id FROM worker_outbox", UUID.class)).isEqualTo(resultId);
        assertThat(readOwnResult().get("eventId")).isEqualTo(resultId.toString());
    }
    WorkerResultRelay relay() { return new WorkerResultRelay(jdbc, manager, new IngestionProperties(false, kafka.getBootstrapServers(), "test", "test")); }
    Map<?, ?> readOwnResult() {
        try (var client = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers(), "group.id", UUID.randomUUID().toString(),
                "auto.offset.reset", "earliest", "enable.auto.commit", false, "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            client.subscribe(List.of("video.processing.results.v1")); long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < end) for (var record : client.poll(Duration.ofMillis(200))) {
                if (record.key().equals(videoId.toString())) return EventFixture.mapper.readValue(record.value(), Map.class);
            }
        }
        throw new AssertionError("Result missing from Kafka.");
    }
}
