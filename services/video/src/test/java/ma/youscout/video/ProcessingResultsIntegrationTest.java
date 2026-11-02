package ma.youscout.video;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.*;
import com.jayway.jsonpath.JsonPath;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProcessingResultsIntegrationTest {
    @Container static final MongoDBContainer mongoDb = new MongoDBContainer("mongo:7").withReplicaSet();
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    static final OidcFixture oidc = new OidcFixture();
    @Autowired MongoTemplate mongo;
    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager manager;
    @Autowired ProcessingResultStore store;
    @MockitoBean(name = "videoCredentialsProvider", answers = org.mockito.Answers.CALLS_REAL_METHODS) AwsCredentialsProvider credentials;
    @Value("${local.server.port}") int port;
    UUID videoId, sourceEventId, attemptId;
    String topic;
    ProcessingResultConsumer consumer;
    KafkaProducer<String, String> producer;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongoDb.getReplicaSetUrl("youscout_video"));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        registry.add("youscout.storage.s3.bucket", () -> "test-bucket");
    }
    @BeforeEach void setup() throws Exception {
        when(credentials.resolveCredentials()).thenReturn(AwsBasicCredentials.create("test-only", "test-only"));
        mongo.remove(new Query(), "processing_result_inbox"); mongo.remove(new Query(), "rejected_processing_results");
        mongo.remove(new Query(), "video_outbox"); mongo.remove(new Query(), "videos");
        videoId = UUID.randomUUID(); attemptId = UUID.randomUUID();
        mongo.insert(new Document("_id", videoId.toString()).append("id", videoId.toString())
                .append("ownerSubject", "alice").append("title", "Clip").append("s3Bucket", "test-bucket")
                .append("s3Key", "videos/" + videoId + "/source").append("contentType", "video/mp4")
                .append("sizeBytes", 12L).append("idempotencyKey", UUID.randomUUID().toString())
                .append("status", "UPLOADED").append("completedAt", new Date()).append("sourceETag", "\"source-etag\"")
                .append("processingStatus", "WAITING").append("publicationStatus", "UNPUBLISHED"), "videos");
        new TransactionTemplate(manager).executeWithoutResult(tx -> outbox.enqueue(videoId));
        sourceEventId = UUID.fromString(mongo.findOne(new Query(Criteria.where("aggregateId").is(videoId.toString())),
                Document.class, "video_outbox").getString("id"));
        topic = "video.results.test." + UUID.randomUUID();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(), "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        consumer = new ProcessingResultConsumer(store, kafka.getBootstrapServers(), topic, "video-test-" + UUID.randomUUID());
    }
    @AfterEach void cleanup() { consumer.stop(); producer.close(Duration.ofSeconds(3)); }
    @AfterAll static void stopOidc() { oidc.server.stop(0); }
    Map<String, Object> event(boolean success) {
        var data = new LinkedHashMap<String, Object>();
        data.put("videoId", videoId); data.put("sourceEventId", sourceEventId); data.put("attemptId", attemptId);
        data.put("sourceETag", "\"source-etag\""); data.put("s3Bucket", "test-bucket");
        if (success) {
            data.put("videoKey", "processed/" + videoId + "/" + attemptId + "/video.mp4");
            data.put("thumbnailKey", "processed/" + videoId + "/" + attemptId + "/thumbnail.jpg");
            data.put("durationMillis", 2000); data.put("width", 320); data.put("height", 240);
        } else data.put("errorCode", "INVALID_MEDIA");
        return new LinkedHashMap<>(Map.of("eventId", UUID.randomUUID(), "eventType", success ? "VideoProcessed" : "VideoProcessingFailed",
                "eventVersion", 1, "producer", "media-worker", "occurredAt", "2026-10-29T12:00:00Z", "data", data));
    }
    @Test void realKafkaResultMakesVideoReadyAndReplayPreservesDates() throws Exception {
        consumer.start(); var event = event(true); send(event);
        await(() -> "READY".equals(status()));
        var first = mongo.findById(videoId.toString(), Document.class, "videos");
        send(event);
        await(() -> mongo.count(new Query(), "processing_result_inbox") == 1);
        assertThat(mongo.findById(videoId.toString(), Document.class, "videos")).isEqualTo(first);
        assertThat(first.getString("status")).isEqualTo("UPLOADED");
        assertThat(first.getString("processedVideoKey")).isEqualTo("processed/" + videoId + "/" + attemptId + "/video.mp4");
    }
    @Test void permanentFailureCannotBeOverwrittenByLateSuccess() throws Exception {
        consumer.start(); send(event(false)); await(() -> "FAILED".equals(status()));
        send(event(true)); await(() -> mongo.count(new Query(), "rejected_processing_results") == 1);
        assertThat(status()).isEqualTo("FAILED");
        assertThat(mongo.findById(videoId.toString(), Document.class, "videos").getString("processingError")).isEqualTo("INVALID_MEDIA");
    }
    @Test void unknownSourceOrDifferentEtagCannotMutateVideo() {
        var value = event(true); data(value).put("sourceETag", "other-etag");
        store.accept(ProcessingResult.decode(videoId.toString(), ProcessingResult.JSON.writeValueAsString(value)), topic, 0, 0);
        var unknown = event(true); data(unknown).put("sourceEventId", UUID.randomUUID());
        store.accept(ProcessingResult.decode(videoId.toString(), ProcessingResult.JSON.writeValueAsString(unknown)), topic, 0, 1);
        assertThat(status()).isEqualTo("WAITING");
        assertThat(mongo.count(new Query(), "rejected_processing_results")).isEqualTo(2);
    }
    @Test void inboxFailureRollsBackVideoState() {
        var result = ProcessingResult.decode(videoId.toString(), ProcessingResult.JSON.writeValueAsString(event(true)));
        rejectCollectionWrites("processing_result_inbox", true);
        try { assertThatThrownBy(() -> store.accept(result, topic, 0, 0)).isInstanceOf(RuntimeException.class); }
        finally { rejectCollectionWrites("processing_result_inbox", false); }
        assertThat(status()).isEqualTo("WAITING");
        store.accept(result, topic, 0, 0); assertThat(status()).isEqualTo("READY");
    }
    @Test void malformedResultDoesNotBlockFollowingValidResult() throws Exception {
        consumer.start(); producer.send(new ProducerRecord<>(topic, 0, videoId.toString(), "{\"token\":\"not-stored\"}")).get(10, TimeUnit.SECONDS);
        send(event(true)); await(() -> "READY".equals(status()));
        assertThat(mongo.findOne(new Query(), Document.class, "rejected_processing_results").getString("reason")).isEqualTo("INVALID_RESULT");
    }
    @Test void processingDetailsArePrivateAndExposeNoStorageKeys() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String owner : List.of("alice", "bob")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/videos/" + videoId))
                    .header("Authorization", "Bearer " + oidc.profileToken(owner, null, owner)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(owner.equals("alice") ? 200 : 404);
                assertThat(response.body()).doesNotContain("s3_key", "test-bucket", "source-etag");
            }
        }
    }
    @SuppressWarnings("unchecked") Map<String, Object> data(Map<String, Object> value) { return (Map<String, Object>) value.get("data"); }
    @Test void onlyOwnerOfReadyVideoReceivesShortLivedGetLinks() throws Exception {
        var result = ProcessingResult.decode(videoId.toString(), ProcessingResult.JSON.writeValueAsString(event(true)));
        store.accept(result, topic, 0, 0);
        var response = get("/v1/videos/" + videoId + "/playback", oidc.profileToken("alice", null, "alice"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var json = JsonPath.parse(response.body());
        for (String field : List.of("videoUrl", "thumbnailUrl")) {
            var url = URI.create(json.read("$." + field, String.class));
            assertThat(url.getScheme()).isEqualTo("https");
            assertThat(url.getPath()).startsWith("/processed/" + videoId + "/" + attemptId + "/");
            assertThat(url.getQuery()).contains("X-Amz-Expires=120", "X-Amz-Signature=");
            assertThat(url.getPath()).doesNotContain("source");
        }
        assertThat(get("/v1/videos/" + videoId + "/playback", oidc.profileToken("bob", null, "bob")).statusCode()).isEqualTo(404);
        assertThat(get("/v1/videos/" + videoId + "/playback", oidc.roleToken("admin")).statusCode()).isEqualTo(404);
    }
    @Test void pendingFailedAndUnauthenticatedPlaybackAreDenied() throws Exception {
        String path = "/v1/videos/" + videoId + "/playback";
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, oidc.roleToken()).statusCode()).isEqualTo(403);
        assertThat(get(path, oidc.profileToken("alice", null, "alice")).statusCode()).isEqualTo(409);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(videoId.toString())), new Update().set("processingStatus", "FAILED"), "videos");
        assertThat(get(path, oidc.profileToken("alice", null, "alice")).statusCode()).isEqualTo(409);
    }
    @Test void signingFailureAndUnexpectedStorageDoNotExposeLinksOrDiagnostics() throws Exception {
        store.accept(ProcessingResult.decode(videoId.toString(), ProcessingResult.JSON.writeValueAsString(event(true))), topic, 0, 0);
        when(credentials.resolveCredentials()).thenThrow(SdkClientException.create("sensitive-sdk-diagnostic"));
        var response = get("/v1/videos/" + videoId + "/playback", oidc.profileToken("alice", null, "alice"));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("sensitive-sdk-diagnostic", "X-Amz", "processed/");
        org.mockito.Mockito.doReturn(AwsBasicCredentials.create("test-only", "test-only")).when(credentials).resolveCredentials();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(videoId.toString())), new Update().set("processedVideoKey", "videos/foreign/source"), "videos");
        assertThat(get("/v1/videos/" + videoId + "/playback", oidc.profileToken("alice", null, "alice")).statusCode()).isEqualTo(503);
    }
    @Test void listIsOwnerScopedPaginatedAndNeverContainsSignedLinks() throws Exception {
        for (int i = 0; i < 21; i++) {
            String id = UUID.randomUUID().toString();
            mongo.insert(new Document("_id", id).append("id", id).append("ownerSubject", "alice")
                    .append("title", "Own clip").append("s3Bucket", "test-bucket")
                    .append("s3Key", UUID.randomUUID().toString()).append("contentType", "video/mp4")
                    .append("sizeBytes", 12L).append("idempotencyKey", UUID.randomUUID().toString())
                    .append("status", "PENDING_UPLOAD").append("publicationStatus", "UNPUBLISHED"), "videos");
        }
        var response = get("/v1/videos", oidc.profileToken("alice", null, "alice"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(response.body()).read("$.items", List.class)).hasSize(20);
        assertThat(JsonPath.parse(response.body()).read("$.hasMore", Boolean.class)).isTrue();
        var next = get("/v1/videos?offset=20", oidc.profileToken("alice", null, "alice"));
        assertThat(JsonPath.parse(next.body()).read("$.items", List.class)).hasSize(2);
        assertThat(JsonPath.parse(next.body()).read("$.hasMore", Boolean.class)).isFalse();
        var other = get("/v1/videos", oidc.profileToken("bob", null, "bob"));
        assertThat(JsonPath.parse(other.body()).read("$.items", List.class)).isEmpty();
        assertThat(response.body()).doesNotContain("test-bucket", "X-Amz", "source-etag", "fixture-key");
        assertThat(get("/v1/videos?offset=-1", oidc.profileToken("alice", null, "alice")).statusCode()).isEqualTo(400);
    }
    HttpResponse<String> get(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    String status() { return mongo.findById(videoId.toString(), Document.class, "videos").getString("processingStatus"); }
    void rejectCollectionWrites(String collection, boolean reject) {
        mongo.getDb().runCommand(new Document("collMod", collection).append("validator",
                reject ? new Document("$expr", false) : new Document()));
    }
    void send(Map<String, Object> value) throws Exception {
        producer.send(new ProducerRecord<>(topic, 0, videoId.toString(), ProcessingResult.JSON.writeValueAsString(value))).get(10, TimeUnit.SECONDS);
    }
    void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) { if (condition.call()) return; Thread.sleep(100); }
        fail("Result ingestion did not reach the expected state.");
    }
}
