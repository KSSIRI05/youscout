package ma.youscout.video;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.*;

/** Retrait et rétablissement par un administrateur sur MongoDB réel ; aucun appel AWS ni Kafka. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ModerationIntegrationTest {
    @Container static final MongoDBContainer mongoDb = new MongoDBContainer("mongo:7").withReplicaSet();
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Autowired MongoTemplate mongo;
    @Value("${local.server.port}") int port;
    final String admin = oidc.roleToken("admin");
    final String alice = oidc.profileToken("alice", null, "alice");
    final String bob = oidc.profileToken("bob", null, "bob");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> mongoDb.getReplicaSetUrl("youscout_video"));
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
    }
    @BeforeEach void clear() { mongo.remove(new Query(), "video_outbox"); mongo.remove(new Query(), "videos"); }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void takedownHidesThePublishedVideoButKeepsItForTheOwner() throws Exception {
        UUID id = create("alice", "PUBLISHED");
        var removed = send("POST", "/v1/videos/admin/" + id + "/takedown", admin, "{\"note\":\"Violence\"}");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(removed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var view = JsonPath.parse(removed.body());
        assertThat(view.read("$.moderationStatus", String.class)).isEqualTo("REMOVED");
        assertThat(view.read("$.moderationNote", String.class)).isEqualTo("Violence");
        assertThat(view.read("$.ownerSubject", String.class)).isEqualTo("alice");

        assertThat(send("GET", "/v1/videos/" + id + "/publication", bob, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/videos/" + id + "/public-playback", bob, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/videos/" + id + "/playback", alice, null).statusCode()).isEqualTo(409);
        assertThat(JsonPath.parse(send("GET", "/v1/videos/" + id, alice, null).body()).read("$.moderationStatus", String.class)).isEqualTo("REMOVED");
        assertThat(send("GET", "/v1/videos", alice, null).body()).contains(id.toString());

        assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", admin, null).statusCode()).isEqualTo(200);
        var events = events(id);
        assertThat(events).hasSize(1);
        Document data = events.getFirst().get("payload", Document.class).get("data", Document.class);
        assertThat(data.getString("action")).isEqualTo("REMOVED");
        assertThat(data.getLong("version")).isEqualTo(1L);
        assertThat(data.keySet()).containsExactlyInAnyOrder("videoId", "ownerSubject", "action", "version", "moderatedAt");
    }

    @Test void restoreMakesTheVideoVisibleAgainWithAHigherVersion() throws Exception {
        UUID id = create("alice", "PUBLISHED");
        send("POST", "/v1/videos/admin/" + id + "/takedown", admin, null);
        var restored = send("POST", "/v1/videos/admin/" + id + "/restore", admin, "{}");
        assertThat(restored.statusCode()).withFailMessage(restored.statusCode() + " " + restored.body()).isEqualTo(200);
        assertThat(JsonPath.parse(restored.body()).read("$.moderationStatus", String.class)).isEqualTo("ACTIVE");
        assertThat(send("GET", "/v1/videos/" + id + "/publication", bob, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/v1/videos/admin/" + id + "/restore", admin, null).statusCode()).isEqualTo(200);
        assertThat(events(id).stream().map(e -> e.get("payload", Document.class).get("data", Document.class))
            .map(d -> d.getString("action") + ":" + d.getLong("version")).toList()).containsExactly("REMOVED:1", "ACTIVE:2");
    }

    @Test void aRemovedVideoCannotBePublished() throws Exception {
        UUID id = create("alice", "UNPUBLISHED");
        send("POST", "/v1/videos/admin/" + id + "/takedown", admin, null);
        assertThat(send("POST", "/v1/videos/" + id + "/publish", alice, null).statusCode()).isEqualTo(409);
        assertThat(video(id).getString("publicationStatus")).isEqualTo("UNPUBLISHED");
    }

    @Test void onlyAdminsModerateAndInputsAreChecked() throws Exception {
        UUID id = create("alice", "PUBLISHED");
        assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", null, null).statusCode()).isEqualTo(401);
        for (String token : List.of(alice, bob))
            assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", token, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/videos/admin/" + id, alice, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/videos/admin/" + UUID.randomUUID() + "/takedown", admin, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", admin, "{\"note\":\"" + "x".repeat(501) + "\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", admin, "{\"moderationStatus\":\"ACTIVE\"}").statusCode()).isEqualTo(400);
        assertThat(ModerationService.status(video(id))).isEqualTo("ACTIVE");
        assertThat(events(id)).isEmpty();

        send("DELETE", "/v1/videos/" + id, alice, null);
        assertThat(send("POST", "/v1/videos/admin/" + id + "/takedown", admin, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/videos/admin/" + id, admin, null).statusCode()).isEqualTo(404);
    }

    // A02/A06 : liste paginée de toutes les vidéos avec leur auteur, filtres et totaux, réservés aux administrateurs.
    @Test void adminsListAndCountEveryVideo() throws Exception {
        UUID oldest = create("alice", "PUBLISHED"), middle = create("bob", "UNPUBLISHED"), newest = create("alice", "PUBLISHED"),
            gone = create("bob", "PUBLISHED");
        long at = 1_700_000_000_000L;
        for (UUID id : List.of(oldest, middle, newest, gone))
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id.toString())),
                new org.springframework.data.mongodb.core.query.Update().set("createdAt", new Date(at += 1000)), "videos");
        send("POST", "/v1/videos/admin/" + newest + "/takedown", admin, null);
        send("DELETE", "/v1/videos/" + gone, bob, null);

        var page = JsonPath.parse(send("GET", "/v1/videos/admin?limit=2", admin, null).body());
        assertThat(page.read("$[*].id", List.class)).containsExactly(newest.toString(), middle.toString());
        assertThat(page.read("$[0].ownerSubject", String.class)).isEqualTo("alice");
        assertThat(page.read("$[0].moderationStatus", String.class)).isEqualTo("REMOVED");
        assertThat(JsonPath.parse(send("GET", "/v1/videos/admin?before=" + middle, admin, null).body()).read("$[*].id", List.class))
            .containsExactly(oldest.toString());
        assertThat(JsonPath.parse(send("GET", "/v1/videos/admin?owner=alice&publication=PUBLISHED", admin, null).body()).read("$.length()", Integer.class))
            .isEqualTo(2);
        assertThat(JsonPath.parse(send("GET", "/v1/videos/admin?removed=true", admin, null).body()).read("$[*].id", List.class))
            .containsExactly(newest.toString());
        assertThat(send("GET", "/v1/videos/admin?publication=DRAFT", admin, null).statusCode()).isEqualTo(400);
        var totals = JsonPath.parse(send("GET", "/v1/videos/admin/stats", admin, null).body());
        assertThat(totals.read("$.videos", Integer.class)).isEqualTo(3);
        assertThat(totals.read("$.published", Integer.class)).isEqualTo(2);
        assertThat(totals.read("$.removed", Integer.class)).isEqualTo(1);
        for (String path : List.of("/v1/videos/admin", "/v1/videos/admin/stats"))
            assertThat(send("GET", path, alice, null).statusCode()).isEqualTo(403);
    }

    // U22 : la fermeture d'un compte supprime toutes ses vidéos, retirées ou non, avec un VideoDeleted chacune.
    @Test void accountClosureDeletesEveryVideoOfTheAccountOnce() throws Exception {
        UUID published = create("alice", "PUBLISHED"), draft = create("alice", "UNPUBLISHED"), other = create("bob", "PUBLISHED");
        send("POST", "/v1/videos/admin/" + draft + "/takedown", admin, null);
        assertThat(send("DELETE", "/internal/accounts/alice", null, null).statusCode()).isEqualTo(401);
        for (String token : List.of(alice, admin))
            assertThat(send("DELETE", "/internal/accounts/alice", token, null).statusCode()).isEqualTo(403);
        for (int attempt = 0; attempt < 2; attempt++)
            assertThat(send("DELETE", "/internal/accounts/alice", oidc.roleToken("account-closure"), null).statusCode()).isEqualTo(204);

        assertThat(video(published).get("deletedAt")).isNotNull();
        assertThat(video(draft).get("deletedAt")).isNotNull();
        assertThat(video(other).get("deletedAt")).isNull();
        var deleted = mongo.find(Query.query(Criteria.where("eventType").is("VideoDeleted")), Document.class, "video_outbox");
        assertThat(deleted).extracting(e -> e.getString("aggregateId"))
            .containsExactlyInAnyOrder(published.toString(), draft.toString());
    }

    UUID create(String owner, String publication) {
        UUID id = UUID.randomUUID();
        mongo.insert(new Document("_id", id.toString()).append("id", id.toString()).append("ownerSubject", owner)
                .append("title", "Moderation fixture").append("s3Bucket", "test-bucket").append("s3Key", "videos/" + id + "/source")
                .append("contentType", "video/mp4").append("sizeBytes", 12L).append("idempotencyKey", UUID.randomUUID().toString())
                .append("status", "UPLOADED").append("completedAt", new Date()).append("sourceETag", "etag")
                .append("processingStatus", "READY").append("processingUpdatedAt", new Date())
                .append("publicationStatus", publication).append("publishedAt", publication.equals("PUBLISHED") ? new Date() : null)
                .append("skills", List.of()).append("hashtags", List.of()), "videos");
        return id;
    }
    Document video(UUID id) { return mongo.findById(id.toString(), Document.class, "videos"); }
    List<Document> events(UUID id) {
        return mongo.find(Query.query(Criteria.where("aggregateId").is(id.toString()).and("eventType").is("VideoModerated"))
                .with(org.springframework.data.domain.Sort.by("createdAt")), Document.class, "video_outbox");
    }
    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
