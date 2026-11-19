package ma.youscout.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL et Kafka réels : les événements des cinq producteurs sont publiés tels que leurs outbox les écrivent.
 * L'envoi FCM est remplacé par une doublure (voir PushSenderTest pour le protocole).
 */
// Testcontainers est déclaré avant Spring : le contexte (consommateur et envoi push) s'arrête avant les conteneurs.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class NotificationIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    static final List<String> TOPICS = List.of("user.relationships.v1", "video.published.v2", "video.deleted.v1",
        "interaction.events.v1", "chat.messages.v1");
    static KafkaProducer<String, String> producer;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbc;
    @MockitoBean PushSender push;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get(30, TimeUnit.SECONDS);
        }
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
            "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("notification.ingestion.bootstrap-servers", kafka::getBootstrapServers);
        r.add("notification.device-key", () -> "test-device-key");
        r.add("notification.push.poll-ms", () -> "100");
    }

    @BeforeEach void clear() throws Exception {
        for (String table : List.of("notifications", "relation_follows", "relation_blocks", "inactive_accounts", "devices"))
            jdbc.update("DELETE FROM " + table);
        when(push.enabled()).thenReturn(false);
    }

    @AfterAll static void cleanup() {
        producer.close(Duration.ofSeconds(3));
        client.close();
        oidc.server.stop(0);
    }

    @Test void oldFollowsUpdateTheProjectionSilentlyAndUnfollowWithdrawsTheUnreadOne() throws Exception {
        // Un suivi rejoué du rattrapage de User date de sa création : il est projeté sans notifier.
        send("user.relationships.v1", "youscout-user-relationships-v1", envelope("UserRelationChanged", "user-service",
            Map.of("action", "FOLLOW", "actorSubject", "old-fan", "targetSubject", "alice"), Instant.now().minus(Duration.ofDays(3))));
        send("user.relationships.v1", "youscout-user-relationships-v1", envelope("UserRelationshipsSnapshotComplete", "user-service", Map.of()));
        String replayed = relation("FOLLOW", "bob", "alice");
        relation("FOLLOW", "carol", "alice");
        awaitCount("SELECT count(*) FROM relation_follows WHERE followed_subject = 'alice'", 3);
        awaitCount("SELECT count(*) FROM notifications WHERE recipient_subject = 'alice'", 2);
        // Le même événement relu après une panne n'est appliqué qu'une fois.
        send("user.relationships.v1", "youscout-user-relationships-v1", replayed);
        relation("UNFOLLOW", "carol", "alice");
        awaitCount("SELECT count(*) FROM relation_follows WHERE follower_subject = 'carol'", 0);
        assertThat(actors("alice")).containsExactly("FOLLOWED bob");
    }

    @Test void publicationsReachActiveUnblockedFollowersAndDisappearWithTheVideo() throws Exception {
        for (String fan : List.of("bob", "carol", "dave", "erin")) relation("FOLLOW", fan, "alice");
        relation("BLOCK", "carol", "alice");
        send("user.relationships.v1", "youscout-user-relationships-v1",
            envelope("UserAccountChanged", "user-service", Map.of("subject", "dave", "status", "CLOSING")));
        awaitCount("SELECT count(*) FROM inactive_accounts", 1);
        UUID video = UUID.randomUUID();
        send("video.published.v2", video.toString(), envelope("VideoPublished", "video-service", Map.of("videoId", video.toString(),
            "ownerSubject", "alice", "title", "But", "publishedAt", Instant.now().toString(), "skills", List.of(), "hashtags", List.of())));
        awaitCount("SELECT count(*) FROM notifications WHERE type = 'VIDEO_PUBLISHED'", 2);
        assertThat(jdbc.queryForList("SELECT recipient_subject FROM notifications WHERE type = 'VIDEO_PUBLISHED' ORDER BY 1", String.class))
            .containsExactly("bob", "erin");
        send("video.deleted.v1", video.toString(), envelope("VideoDeleted", "video-service", Map.of("videoId", video.toString(),
            "ownerSubject", "alice", "deletedAt", Instant.now().toString(), "wasPublished", true)));
        awaitCount("SELECT count(*) FROM notifications WHERE video_id = '" + video + "'", 0);
    }

    @Test void likesCommentsAndRepliesNotifyTheRightPeopleOnce() throws Exception {
        UUID video = UUID.randomUUID(), root = UUID.randomUUID(), reply = UUID.randomUUID(), own = UUID.randomUUID();
        for (int i = 0; i < 2; i++) liked(video, "alice", "bob");
        liked(video, "alice", "alice");
        commented(video, "alice", "bob", root, null);
        commented(video, "alice", "carol", reply, "bob");
        commented(video, "alice", "alice", own, "bob");
        // Les sujets Kafka ne sont pas ordonnés entre eux : le blocage est appliqué avant le like de mallory.
        relation("BLOCK", "alice", "mallory");
        awaitCount("SELECT count(*) FROM relation_blocks", 1);
        liked(video, "alice", "mallory");
        awaitCount("SELECT count(*) FROM notifications", 5);
        Thread.sleep(500);
        assertThat(actors("alice")).containsExactlyInAnyOrder("VIDEO_LIKED bob", "COMMENTED bob", "COMMENTED carol");
        assertThat(actors("bob")).containsExactlyInAnyOrder("REPLIED carol", "REPLIED alice");
        assertThat(jdbc.queryForList("SELECT reason FROM notification_rejected_messages", String.class)).isEmpty();
    }

    @Test void messagesOfAConversationShareOneUnreadNotification() throws Exception {
        String conversation = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) message(conversation, "bob", "alice");
        awaitCount("SELECT coalesce(sum(occurrences), 0) FROM notifications WHERE type = 'MESSAGE'", 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(1);
        String id = jdbc.queryForObject("SELECT id::text FROM notifications", String.class);
        assertThat(http("POST", "/v1/notifications/" + id + "/read", oidc.token("alice"), null).statusCode()).isEqualTo(204);
        message(conversation, "bob", "alice");
        awaitCount("SELECT count(*) FROM notifications WHERE type = 'MESSAGE'", 2);
    }

    @Test void theInboxIsPrivatePagedAndMarkedRead() throws Exception {
        for (String fan : List.of("bob", "carol", "dave")) relation("FOLLOW", fan, "alice");
        relation("FOLLOW", "alice", "bob");
        awaitCount("SELECT count(*) FROM notifications", 4);
        String alice = oidc.token("alice");
        assertThat(http("GET", "/v1/notifications", null, null).statusCode()).isEqualTo(401);
        assertThat(http("GET", "/v1/notifications", oidc.token("alice", "offline_access"), null).statusCode()).isEqualTo(403);

        var unread = http("GET", "/v1/notifications/unread-count", alice, null);
        assertThat(unread.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JsonPath.parse(unread.body()).read("$.count", Integer.class)).isEqualTo(3);
        var first = http("GET", "/v1/notifications?limit=2", alice, null);
        List<String> ids = JsonPath.parse(first.body()).read("$[*].id");
        assertThat(ids).hasSize(2);
        var rest = http("GET", "/v1/notifications?limit=2&before=" + ids.get(1), alice, null);
        assertThat(JsonPath.parse(rest.body()).read("$.length()", Integer.class)).isEqualTo(1);
        assertThat(JsonPath.parse(rest.body()).read("$[0].read", Boolean.class)).isFalse();
        assertThat(http("GET", "/v1/notifications?limit=0", alice, null).statusCode()).isEqualTo(400);

        String bobsNotification = jdbc.queryForObject("SELECT id::text FROM notifications WHERE recipient_subject = 'bob'", String.class);
        assertThat(http("POST", "/v1/notifications/" + bobsNotification + "/read", alice, null).statusCode()).isEqualTo(404);
        assertThat(http("GET", "/v1/notifications?before=" + bobsNotification, alice, null).statusCode()).isEqualTo(400);
        assertThat(http("POST", "/v1/notifications/" + ids.getFirst() + "/read", alice, null).statusCode()).isEqualTo(204);
        assertThat(JsonPath.parse(http("GET", "/v1/notifications/unread-count", alice, null).body()).read("$.count", Integer.class)).isEqualTo(2);
        assertThat(http("POST", "/v1/notifications/read-all", alice, null).statusCode()).isEqualTo(204);
        assertThat(JsonPath.parse(http("GET", "/v1/notifications/unread-count", alice, null).body()).read("$.count", Integer.class)).isZero();
        assertThat(JsonPath.parse(http("GET", "/v1/notifications/unread-count", oidc.token("bob"), null).body()).read("$.count", Integer.class)).isEqualTo(1);
    }

    @Test void devicesAreStoredEncryptedAndReceiveTheGenericPush() throws Exception {
        String alice = oidc.token("alice"), bob = oidc.token("bob");
        assertThat(http("PUT", "/v1/notifications/devices", alice, "{\"platform\":\"ANDROID\",\"token\":\"stale-token\"}").statusCode()).isEqualTo(204);
        assertThat(http("PUT", "/v1/notifications/devices", alice, "{\"platform\":\"WEB\",\"token\":\"good-token\"}").statusCode()).isEqualTo(204);
        for (String body : List.of("{\"platform\":\"PAGER\",\"token\":\"x\"}", "{\"platform\":\"WEB\"}", "{\"platform\":\"WEB\",\"token\":\"x\",\"extra\":1}"))
            assertThat(http("PUT", "/v1/notifications/devices", alice, body).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForList("SELECT token_ciphertext || token_hash FROM devices", String.class))
            .noneSatisfy(stored -> assertThat(stored).contains("good-token"));
        // Le même appareil connecté à un autre compte change de propriétaire, puis revient.
        http("PUT", "/v1/notifications/devices", bob, "{\"platform\":\"WEB\",\"token\":\"good-token\"}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices WHERE subject = 'bob'", Long.class)).isEqualTo(1);
        http("PUT", "/v1/notifications/devices", alice, "{\"platform\":\"WEB\",\"token\":\"good-token\"}");

        when(push.enabled()).thenReturn(true);
        when(push.send(eq("good-token"), anyString(), anyString(), any())).thenReturn(PushSender.Result.SENT);
        when(push.send(eq("stale-token"), anyString(), anyString(), any())).thenReturn(PushSender.Result.INVALID_TOKEN);
        relation("FOLLOW", "bob", "alice");
        relation("FOLLOW", "alice", "carol");
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForList("SELECT push_status FROM notifications ORDER BY recipient_subject",
            String.class).equals(List.of("SENT", "NO_DEVICE")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices WHERE subject = 'alice'", Long.class)).isEqualTo(1);
        org.mockito.Mockito.verify(push).send(eq("good-token"), eq("Nouvel abonné"), eq("Ouvre YouScout pour voir."), any());

        assertThat(http("POST", "/v1/notifications/devices/unregister", alice, "{\"token\":\"good-token\"}").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices", Long.class)).isZero();
    }

    @Test void pushStaysDisabledWithoutFcm() throws Exception {
        relation("FOLLOW", "bob", "alice");
        await().atMost(Duration.ofSeconds(20)).until(() -> "DISABLED".equals(
            jdbc.queryForList("SELECT push_status FROM notifications", String.class).stream().findFirst().orElse(null)));
    }

    @Test void accountClosureErasesTheAccountsNotificationsAndKeepsItSilent() throws Exception {
        relation("FOLLOW", "bob", "alice");
        relation("FOLLOW", "alice", "carol");
        awaitCount("SELECT count(*) FROM notifications", 2);
        http("PUT", "/v1/notifications/devices", oidc.token("alice"), "{\"platform\":\"IOS\",\"token\":\"ios-token\"}");
        assertThat(http("DELETE", "/internal/accounts/alice", null, null).statusCode()).isEqualTo(401);
        assertThat(http("DELETE", "/internal/accounts/alice", oidc.token("svc", "admin"), null).statusCode()).isEqualTo(403);
        for (int attempt = 0; attempt < 2; attempt++)
            assertThat(http("DELETE", "/internal/accounts/alice", oidc.token("svc", "account-closure"), null).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devices", Long.class)).isZero();
        // Un événement tardif du compte fermé ne recrée rien.
        message(UUID.randomUUID().toString(), "alice", "bob");
        relation("FOLLOW", "dave", "bob");
        awaitCount("SELECT count(*) FROM notifications", 1);
        assertThat(actors("bob")).containsExactly("FOLLOWED dave");
    }

    // --- événements tels que les outbox des producteurs les écrivent ---

    private String relation(String action, String actor, String target) throws Exception {
        String event = envelope("UserRelationChanged", "user-service", Map.of("action", action, "actorSubject", actor, "targetSubject", target));
        send("user.relationships.v1", "youscout-user-relationships-v1", event);
        return event;
    }

    private void liked(UUID video, String owner, String actor) throws Exception {
        send("interaction.events.v1", video.toString(), envelope("VideoLiked", "social-service", Map.of("videoId", video.toString(),
            "videoOwnerSubject", owner, "actorSubject", actor, "likedAt", Instant.now().toString())));
    }

    private void commented(UUID video, String owner, String author, UUID comment, String parentAuthor) throws Exception {
        var data = new java.util.HashMap<String, Object>(Map.of("commentId", comment.toString(), "videoId", video.toString(),
            "videoOwnerSubject", owner, "authorSubject", author, "createdAt", Instant.now().toString()));
        data.put("parentId", parentAuthor == null ? null : UUID.randomUUID().toString());
        data.put("parentAuthorSubject", parentAuthor);
        send("interaction.events.v1", video.toString(), envelope("CommentAdded", "social-service", data));
    }

    private void message(String conversation, String sender, String recipient) throws Exception {
        send("chat.messages.v1", conversation, envelope("MessageAccepted", "chat-service", Map.of("messageId", UUID.randomUUID().toString(),
            "conversationId", conversation, "seq", 1, "senderSubject", sender, "recipientSubject", recipient, "acceptedAt", Instant.now().toString())));
    }

    private static String envelope(String type, String producer, Map<String, ?> data) {
        return envelope(type, producer, data, Instant.now());
    }

    private static String envelope(String type, String producer, Map<String, ?> data, Instant occurredAt) {
        return JSON.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "eventType", type, "eventVersion", 1,
            "occurredAt", occurredAt.toString(), "producer", producer, "data", data));
    }

    private static void send(String topic, String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
    }

    private void awaitCount(String sql, long expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(sql, Long.class) == expected);
    }

    private List<String> actors(String recipient) {
        return jdbc.queryForList("SELECT type || ' ' || actor_subject FROM notifications WHERE recipient_subject = ?", String.class, recipient);
    }

    private HttpResponse<String> http(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
