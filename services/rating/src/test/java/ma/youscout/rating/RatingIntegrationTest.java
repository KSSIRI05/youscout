package ma.youscout.rating;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL et JWT réels ; User et Video sont remplacés par les doublures de la fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RatingIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    static final UUID DRIBBLE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID SHOT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("rating.user-service-url", oidc::base);
        r.add("rating.video-service-url", oidc::base);
    }
    @BeforeEach void clear() {
        oidc.reset();
        jdbc.update("TRUNCATE skill_ratings");
    }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void authenticationAndApplicationRoleAreRequired() throws Exception {
        var anonymous = send("GET", path(UUID.randomUUID()), null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(send("GET", path(UUID.randomUUID()), oidc.token("alice", "offline_access"), null).statusCode()).isEqualTo(403);
    }

    @Test void ratingIsUniquePerRaterAndCanBeChangedOrRemoved() throws Exception {
        UUID video = published("bob");
        String alice = oidc.token("alice"), carol = oidc.token("carol");
        assertThat(rate(video, DRIBBLE, alice, 4).statusCode()).isEqualTo(200);
        rate(video, DRIBBLE, alice, 4);
        var changed = rate(video, DRIBBLE, alice, 2);
        assertThat(read(changed, "$.skills[0].count", Integer.class)).isEqualTo(1);
        assertThat(read(changed, "$.skills[0].myScore", Integer.class)).isEqualTo(2);

        var both = rate(video, DRIBBLE, carol, 5);
        assertThat(read(both, "$.skills[0].skillId", String.class)).isEqualTo(DRIBBLE.toString());
        assertThat(read(both, "$.skills[0].count", Integer.class)).isEqualTo(2);
        assertThat(read(both, "$.skills[0].average", Double.class)).isEqualTo(3.5);
        assertThat(read(both, "$.skills[0].myScore", Integer.class)).isEqualTo(5);
        rate(video, SHOT, carol, 3);

        var seenByAlice = send("GET", path(video), alice, null);
        assertThat(seenByAlice.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(read(seenByAlice, "$.skills.length()", Integer.class)).isEqualTo(2);
        assertThat(read(seenByAlice, "$.skills[1].myScore", Integer.class)).isNull();

        var removed = send("DELETE", path(video) + "/skills/" + DRIBBLE, alice, null);
        assertThat(read(removed, "$.skills[0].count", Integer.class)).isEqualTo(1);
        assertThat(read(removed, "$.skills[0].average", Double.class)).isEqualTo(5.0);
        assertThat(send("DELETE", path(video) + "/skills/" + DRIBBLE, alice, null).statusCode()).isEqualTo(200);
    }

    @Test void scoresOutsideOneToFiveAreRejected() throws Exception {
        UUID video = published("bob");
        String alice = oidc.token("alice");
        for (String body : List.of("{\"score\":0}", "{\"score\":6}", "{\"score\":3.5}", "{\"score\":\"4\"}", "{}", "{\"score\":3,\"x\":1}"))
            assertThat(send("PUT", path(video) + "/skills/" + DRIBBLE, alice, body).statusCode()).as(body).isEqualTo(400);
        assertThat(count()).isZero();
    }

    @Test void onlyPublishedVideosAndTheirOwnSkillsCanBeRated() throws Exception {
        String alice = oidc.token("alice");
        assertThat(rate(UUID.randomUUID(), DRIBBLE, alice, 4).statusCode()).isEqualTo(404);
        UUID video = published("bob");
        var otherSkill = rate(video, UUID.randomUUID(), alice, 4);
        assertThat(otherSkill.statusCode()).isEqualTo(404);
        assertThat(otherSkill.body()).contains("pas associée");
        assertThat(count()).isZero();
    }

    @Test void ownersCannotRateThemselvesAndBlocksApplyBothWays() throws Exception {
        UUID video = published("bob");
        assertThat(rate(video, DRIBBLE, oidc.token("bob"), 5).statusCode()).isEqualTo(403);
        oidc.blocks.add(List.of("bob", "alice"));
        assertThat(rate(video, DRIBBLE, oidc.token("alice"), 5).statusCode()).isEqualTo(403);
        oidc.blocks.clear();
        oidc.blocks.add(List.of("carol", "bob"));
        assertThat(rate(video, DRIBBLE, oidc.token("carol"), 5).statusCode()).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test void writesFailClosedWhenVideoIsUnavailable() throws Exception {
        UUID video = published("bob");
        oidc.videoUnavailable = true;
        assertThat(rate(video, DRIBBLE, oidc.token("alice"), 4).statusCode()).isEqualTo(503);
        assertThat(count()).isZero();
    }

    @Test void concurrentChangesKeepASingleRating() throws Exception {
        UUID video = published("bob");
        String alice = oidc.token("alice");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 16; i++) {
                int score = i % 5 + 1;
                results.add(pool.submit(() -> rate(video, DRIBBLE, alice, score).statusCode()));
            }
            for (var result : results) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(count()).isEqualTo(1);
    }

    private UUID published(String owner) {
        UUID video = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), new OidcFixture.Published(owner, List.of(DRIBBLE, SHOT)));
        return video;
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM skill_ratings", Integer.class); }
    private static String path(UUID video) { return "/v1/ratings/videos/" + video; }
    private HttpResponse<String> rate(UUID video, UUID skill, String token, int score) throws Exception {
        return send("PUT", path(video) + "/skills/" + skill, token, "{\"score\":" + score + "}");
    }
    private static <T> T read(HttpResponse<String> response, String path, Class<T> type) {
        return JsonPath.parse(response.body()).read(path, type);
    }
    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
