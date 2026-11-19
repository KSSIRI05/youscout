package ma.youscout.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL et Kafka réels : événements des producteurs comptés par jour, une seule fois, lus par un administrateur. */
// Testcontainers est déclaré avant Spring : le consommateur s'arrête avant les conteneurs.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class AnalyticsIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static KafkaProducer<String, String> producer;

    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of("user.relationships.v1", "video.published.v2", "interaction.events.v1", "chat.messages.v1").stream()
                .map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get(30, TimeUnit.SECONDS);
        }
        producer = new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
            "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("analytics.ingestion.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM daily_activity");
    }

    @AfterAll static void cleanup() {
        producer.close(Duration.ofSeconds(3));
        client.close();
        oidc.server.stop(0);
    }

    @Test void eventsAreCountedOncePerUtcDayAndOtherEventsIgnored() throws Exception {
        Instant now = Instant.now(), twoDaysAgo = now.minus(Duration.ofDays(2));
        String profile = event("UserProfileCreated", "user-service", Map.of("subject", "alice"), now);
        send("user.relationships.v1", profile);
        send("user.relationships.v1", profile);
        send("user.relationships.v1", event("UserProfileCreated", "user-service", Map.of("subject", "bob"), twoDaysAgo));
        send("user.relationships.v1", event("UserRelationChanged", "user-service", Map.of("action", "FOLLOW", "actorSubject", "a", "targetSubject", "b"), now));
        send("video.published.v2", event("VideoPublished", "video-service", Map.of("videoId", UUID.randomUUID().toString()), now));
        for (int i = 0; i < 3; i++) send("interaction.events.v1", event("VideoLiked", "social-service", Map.of("videoId", "v"), now));
        send("interaction.events.v1", event("CommentAdded", "social-service", Map.of("videoId", "v"), now));
        for (int i = 0; i < 2; i++) send("chat.messages.v1", event("MessageAccepted", "chat-service", Map.of("seq", i), twoDaysAgo));
        send("chat.messages.v1", "{not json");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject("SELECT coalesce(sum(count), 0) FROM daily_activity", Long.class) == 9);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT count(*) FROM analytics_rejected_messages", Long.class) == 1);

        var activity = http("/v1/analytics/activity?days=7", oidc.token("moderator", "admin"));
        assertThat(activity.statusCode()).isEqualTo(200);
        assertThat(activity.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var body = JsonPath.parse(activity.body());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(body.read("$.days.length()", Integer.class)).isEqualTo(7);
        assertThat(body.read("$.to", String.class)).isEqualTo(today.toString());
        assertThat(body.read("$.coverageStart", String.class)).isEqualTo(today.minusDays(2).toString());
        assertThat(body.read("$.days[6].newAccounts", Integer.class)).isEqualTo(1);
        assertThat(body.read("$.days[6].videosPublished", Integer.class)).isEqualTo(1);
        assertThat(body.read("$.days[6].likes", Integer.class)).isEqualTo(3);
        assertThat(body.read("$.days[6].comments", Integer.class)).isEqualTo(1);
        assertThat(body.read("$.days[4].newAccounts", Integer.class)).isEqualTo(1);
        assertThat(body.read("$.days[4].messages", Integer.class)).isEqualTo(2);
        assertThat(body.read("$.days[0].likes", Integer.class)).isZero();
    }

    @Test void onlyAdministratorsReadTheActivity() throws Exception {
        assertThat(http("/v1/analytics/activity", null).statusCode()).isEqualTo(401);
        assertThat(http("/v1/analytics/activity", oidc.token("alice")).statusCode()).isEqualTo(403);
        assertThat(http("/v1/analytics/activity?days=91", oidc.token("moderator", "admin")).statusCode()).isEqualTo(400);
        var empty = JsonPath.parse(http("/v1/analytics/activity?days=1", oidc.token("moderator", "admin")).body());
        assertThat(empty.read("$.days.length()", Integer.class)).isEqualTo(1);
    }

    private static String event(String type, String producer, Map<String, ?> data, Instant at) {
        return JSON.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "eventType", type, "eventVersion", 1,
            "occurredAt", at.toString(), "producer", producer, "data", data));
    }

    private static void send(String topic, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, "key", value)).get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> http(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
