package ma.youscout.video;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.*;

/** MongoDB et JWT réels ; publier ne fait aucun appel AWS et ne dépend pas de Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PublicationIntegrationTest {
    @Container static final MongoDBContainer mongoDb = new MongoDBContainer("mongo:7").withReplicaSet();
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Autowired MongoTemplate mongo;
    @Value("${local.server.port}") int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongoDb.getReplicaSetUrl("youscout_video"));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
    }
    @BeforeEach void clear() { mongo.remove(new Query(), "video_outbox"); mongo.remove(new Query(), "videos"); }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void publicationIsSeparateAndVisibleInDetailsAndList() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        String token = oidc.profileToken("alice", null, "alice");
        var before = video(id);
        assertThat(before.getString("publicationStatus")).isEqualTo("UNPUBLISHED");
        assertThat(before.get("publishedAt")).isNull();
        var response = publish(id, token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var body = JsonPath.parse(response.body());
        assertThat(body.read("$.id", String.class)).isEqualTo(id.toString());
        assertThat(body.read("$.publicationStatus", String.class)).isEqualTo("PUBLISHED");
        assertThat(body.read("$.publishedAt", String.class)).isNotBlank();
        var after = video(id);
        for (String column : List.of("status", "processingStatus", "updatedAt", "completedAt", "processingUpdatedAt", "sourceETag"))
            assertThat(after.get(column)).isEqualTo(before.get(column));
        assertThat(JsonPath.parse(get("/v1/videos/" + id, token).body()).read("$.publicationStatus", String.class)).isEqualTo("PUBLISHED");
        assertThat(JsonPath.parse(get("/v1/videos", token).body()).read("$.items[0].publishedAt", String.class))
            .isEqualTo(body.read("$.publishedAt", String.class));
        assertThat(response.body()).doesNotContain("source", "bucket", "X-Amz");
    }
    @Test void replayAndConcurrentCallsPreserveFirstPublicationAndCompletion() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        String token = oidc.profileToken("alice", null, "alice");
        var completionBefore = post("/v1/videos/" + id + "/complete", token, null);
        assertThat(completionBefore.statusCode()).withFailMessage(completionBefore.body()).isEqualTo(200);
        var futures = java.util.stream.IntStream.range(0, 6).mapToObj(n -> CompletableFuture.supplyAsync(() -> {
            try { return publish(id, token, "{}"); }
            catch (Exception error) { throw new RuntimeException(error); }
        })).toList();
        var first = futures.getFirst().join();
        for (var future : futures) {
            assertThat(future.join().statusCode()).isEqualTo(200);
            assertThat(future.join().body()).isEqualTo(first.body());
        }
        assertThat(publish(id, token, null).body()).isEqualTo(first.body());
        assertThat(post("/v1/videos/" + id + "/complete", token, null).body()).isEqualTo(completionBefore.body());
    }
    @Test void ownerAndAdminCannotPublishAnotherAccountsVideo() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        for (String token : List.of(oidc.profileToken("bob", null, "bob"), oidc.roleToken("admin")))
            assertThat(publish(id, token, null).statusCode()).isEqualTo(404);
        assertThat(publish(UUID.randomUUID(), oidc.profileToken("alice", null, "alice"), null).statusCode()).isEqualTo(404);
        assertThat(video(id).getString("publicationStatus")).isEqualTo("UNPUBLISHED");
        UUID ownAdmin = create("role-test", "UPLOADED", "READY");
        assertThat(publish(ownAdmin, oidc.roleToken("admin"), null).statusCode()).isEqualTo(200);
    }
    @Test void authenticationAndRoleAreRequired() throws Exception {
        UUID id = create("role-test", "UPLOADED", "READY");
        assertThat(publish(id, null, null).statusCode()).isEqualTo(401);
        assertThat(publish(id, oidc.roleToken(), null).statusCode()).isEqualTo(403);
        assertThat(get("/v1/videos/" + id + "/publish", oidc.roleToken("user")).statusCode()).isEqualTo(403);
        assertThat(video(id).get("publishedAt")).isNull();
    }
    @Test void publicPlaybackRequiresAnAuthenticatedRoleAndPublishedVideo() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        String path = "/v1/videos/" + id + "/public-playback";

        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, oidc.roleToken()).statusCode()).isEqualTo(403);
        assertThat(get(path, oidc.profileToken("alice", null, "alice")).statusCode()).isEqualTo(404);
    }
    @Test void allUnreadyStatesStayUnpublished() throws Exception {
        String token = oidc.profileToken("alice", null, "alice");
        var ids = new ArrayList<UUID>();
        ids.add(create("alice", "PENDING_UPLOAD", null));
        ids.add(create("alice", "UPLOADED", null));
        ids.add(create("alice", "UPLOADED", "WAITING"));
        ids.add(create("alice", "UPLOADED", "FAILED"));
        for (UUID id : ids) {
            assertThat(publish(id, token, null).statusCode()).isEqualTo(409);
            assertThat(video(id).getString("publicationStatus")).isEqualTo("UNPUBLISHED");
            assertThat(video(id).get("publishedAt")).isNull();
        }
    }
    @Test void clientCannotChooseOwnerStatusOrPublicationDate() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        for (String body : List.of("{\"ownerSubject\":\"alice\"}", "{\"publicationStatus\":\"PUBLISHED\"}",
                "{\"publishedAt\":\"2026-01-01T00:00:00Z\"}", "[]"))
            assertThat(publish(id, oidc.profileToken("alice", null, "alice"), body).statusCode()).isEqualTo(400);
        assertThat(video(id).getString("publicationStatus")).isEqualTo("UNPUBLISHED");
    }
    @Test void failedWriteRollsBackAndCanBeRetried() throws Exception {
        UUID id = create("alice", "UPLOADED", "READY");
        String token = oidc.profileToken("alice", null, "alice");
        rejectPublicationWrites(true);
        try {
            var response = publish(id, token, null);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).doesNotContain("test_no_publication", "UPDATE videos");
        } finally { rejectPublicationWrites(false); }
        assertThat(video(id).get("publishedAt")).isNull();
        assertThat(publish(id, token, null).statusCode()).isEqualTo(200);
    }
    UUID create(String owner, String uploadStatus, String processingStatus) {
        UUID id = UUID.randomUUID();
        boolean uploaded = "UPLOADED".equals(uploadStatus);
        mongo.insert(new Document("_id", id.toString()).append("id", id.toString()).append("ownerSubject", owner)
                .append("title", "Publication fixture").append("s3Bucket", "test-bucket")
                .append("s3Key", "videos/" + id + "/source").append("contentType", "video/mp4")
                .append("sizeBytes", 12L).append("idempotencyKey", UUID.randomUUID().toString())
                .append("status", uploadStatus).append("completedAt", uploaded ? new Date() : null)
                .append("sourceETag", uploaded ? "fixture-etag" : null).append("processingStatus", processingStatus)
                .append("processingUpdatedAt", new Date()).append("publicationStatus", "UNPUBLISHED")
                .append("skills", List.of()).append("hashtags", List.of()), "videos");
        return id;
    }
    Document video(UUID id) { return mongo.findById(id.toString(), Document.class, "videos"); }
    void rejectPublicationWrites(boolean reject) {
        mongo.getDb().runCommand(new Document("collMod", "videos").append("validator", reject
                ? new Document("publicationStatus", new Document("$ne", "PUBLISHED")) : new Document()));
    }
    HttpResponse<String> publish(UUID id, String token, String body) throws Exception {
        return post("/v1/videos/" + id + "/publish", token, body);
    }
    HttpResponse<String> post(String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-User-Id", "alice"); // Cet en-tête ne remplace jamais le sub signé.
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
