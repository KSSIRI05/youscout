package ma.youscout.social;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.util.*;
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
class SocialIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("social.user-service-url", oidc::base);
        r.add("social.video-service-url", oidc::base);
    }
    @BeforeEach void clear() {
        oidc.reset();
        jdbc.update("TRUNCATE moderation_decisions, comment_reports, video_likes, video_comments, video_reports");
    }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void authenticationAndApplicationRoleAreRequired() throws Exception {
        var anonymous = send("GET", "/v1/social/videos/" + UUID.randomUUID() + "/likes", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(send("GET", "/v1/social/videos/" + UUID.randomUUID() + "/likes", oidc.token("alice", "offline_access"), null).statusCode()).isEqualTo(403);
        // Les messages directs ont migré vers Chat : l'ancienne route n'existe plus.
        assertThat(send("GET", "/v1/social/messages?with=bob", oidc.token("alice"), null).statusCode()).isEqualTo(404);
    }

    @Test void likesRequireAPublishedVideoAndAreIdempotent() throws Exception {
        UUID video = UUID.randomUUID();
        String alice = oidc.token("alice");
        assertThat(send("PUT", likes(video), alice, null).statusCode()).isEqualTo(404);
        oidc.publishedVideos.put(video.toString(), "bob");
        for (int attempt = 0; attempt < 2; attempt++) {
            var liked = send("PUT", likes(video), alice, null);
            assertThat(liked.statusCode()).isEqualTo(200);
            assertThat(JsonPath.parse(liked.body()).read("$.count", Integer.class)).isEqualTo(1);
            assertThat(JsonPath.parse(liked.body()).read("$.liked", Boolean.class)).isTrue();
        }
        assertThat(JsonPath.parse(send("DELETE", likes(video), alice, null).body()).read("$.count", Integer.class)).isZero();
    }

    @Test void writesFailClosedWhenVideoIsUnavailable() throws Exception {
        UUID video = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), "bob");
        oidc.videoUnavailable = true;
        assertThat(send("PUT", likes(video), oidc.token("alice"), null).statusCode()).isEqualTo(503);
        assertThat(send("POST", comments(video), oidc.token("alice"), "{\"body\":\"Bravo\"}").statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_likes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_comments", Integer.class)).isZero();
    }

    @Test void blocksPreventInteractionsInBothDirectionsButNotReports() throws Exception {
        UUID video = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), "bob");
        oidc.profiles.addAll(List.of("alice", "bob", "carol"));
        String carolComment = JsonPath.parse(send("POST", comments(video), oidc.token("carol"), "{\"body\":\"Superbe\"}").body()).read("$.id");
        oidc.blocks.add(List.of("bob", "alice"));
        oidc.blocks.add(List.of("alice", "carol"));
        String alice = oidc.token("alice");

        assertThat(send("PUT", likes(video), alice, null).statusCode()).isEqualTo(403);
        assertThat(send("POST", comments(video), alice, "{\"body\":\"Salut\"}").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/social/videos/" + video + "/reports", alice, "{\"reason\":\"Contenu trompeur\"}").statusCode()).isEqualTo(202);
        // Les commentaires d'un compte bloqué disparaissent pour l'appelant, mais pas pour les autres.
        assertThat(send("GET", comments(video), alice, null).body()).doesNotContain(carolComment);
        assertThat(send("GET", comments(video), oidc.token("dave"), null).body()).contains(carolComment);
    }

    @Test void commentsReturnTheLatestPageAndPageBackwardsWithACursor() throws Exception {
        UUID video = UUID.randomUUID();
        for (int n = 0; n < 120; n++)
            jdbc.update("INSERT INTO video_comments(id, video_id, author_subject, body, created_at) VALUES (?, ?, 'carol', ?, timestamptz '2026-01-01T00:00:00Z' + ? * interval '1 second')",
                UUID.randomUUID(), video, "c" + n, n);
        String token = oidc.token("alice");
        var latest = JsonPath.parse(send("GET", comments(video), token, null).body());
        List<String> bodies = latest.read("$[*].body");
        assertThat(bodies).hasSize(50).startsWith("c70").endsWith("c119");
        var older = JsonPath.parse(send("GET", comments(video) + "?before=" + latest.read("$[0].id") + "&limit=100", token, null).body());
        List<String> olderBodies = older.read("$[*].body");
        assertThat(olderBodies).hasSize(70).startsWith("c0").endsWith("c69");
        for (String query : List.of("?limit=0", "?limit=101", "?before=" + UUID.randomUUID(), "?before=not-a-uuid"))
            assertThat(send("GET", comments(video) + query, token, null).statusCode()).isEqualTo(400);
    }

    @Test void repliesStayOneLevelDeepOnTheSameVideo() throws Exception {
        UUID video = UUID.randomUUID(), other = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), "bob");
        oidc.publishedVideos.put(other.toString(), "bob");
        String alice = oidc.token("alice");
        String root = JsonPath.parse(send("POST", comments(video), alice, "{\"body\":\"Quel geste\"}").body()).read("$.id");
        var reply = send("POST", comments(video), oidc.token("bob"), "{\"body\":\"Merci\",\"parentId\":\"" + root + "\"}");
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(reply.body()).read("$.parentId", String.class)).isEqualTo(root);
        String replyId = JsonPath.parse(reply.body()).read("$.id");

        assertThat(send("POST", comments(video), alice, "{\"body\":\"x\",\"parentId\":\"" + replyId + "\"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", comments(other), alice, "{\"body\":\"x\",\"parentId\":\"" + root + "\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", comments(video), alice, "{\"body\":\"x\",\"parentId\":\"" + UUID.randomUUID() + "\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", comments(video), alice, "{\"body\":\"x\",\"parentId\":42}").statusCode()).isEqualTo(400);
    }

    @Test void authorOrVideoOwnerCanDeleteAComment() throws Exception {
        UUID video = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), "bob");
        String first = JsonPath.parse(send("POST", comments(video), oidc.token("carol"), "{\"body\":\"Spam\"}").body()).read("$.id");
        String second = JsonPath.parse(send("POST", comments(video), oidc.token("carol"), "{\"body\":\"Oups\"}").body()).read("$.id");

        assertThat(send("DELETE", "/v1/social/comments/" + first, oidc.token("dave"), null).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/v1/social/comments/" + first, oidc.token("bob"), null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/v1/social/comments/" + second, oidc.token("carol"), null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/v1/social/comments/" + second, oidc.token("carol"), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", comments(video), oidc.token("dave"), null).body()).isEqualTo("[]");
    }

    @Test void reportsRequireAPublishedVideo() throws Exception {
        String alice = oidc.token("alice");
        assertThat(send("POST", "/v1/social/videos/" + UUID.randomUUID() + "/reports", alice, "{\"reason\":\"Spam\"}").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_reports", Integer.class)).isZero();
    }

    @Test void accountClosureErasesLikesAndCommentTextButKeepsReplies() throws Exception {
        UUID video = UUID.randomUUID();
        oidc.publishedVideos.put(video.toString(), "bob");
        send("PUT", likes(video), oidc.token("alice"), null);
        send("PUT", likes(video), oidc.token("carol"), null);
        String root = JsonPath.parse(send("POST", comments(video), oidc.token("alice"), "{\"body\":\"Bravo\"}").body()).read("$.id");
        send("POST", comments(video), oidc.token("carol"), "{\"body\":\"Merci\",\"parentId\":\"" + root + "\"}");
        send("POST", comments(video), oidc.token("carol"), "{\"body\":\"Super\"}");

        assertThat(send("DELETE", "/internal/accounts/alice", null, null).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", "/internal/accounts/alice", oidc.token("svc", "admin"), null).statusCode()).isEqualTo(403);
        for (int attempt = 0; attempt < 2; attempt++)
            assertThat(send("DELETE", "/internal/accounts/alice", oidc.token("svc", "account-closure"), null).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("SELECT user_subject FROM video_likes", String.class)).containsExactly("carol");
        assertThat(jdbc.queryForList("SELECT concat_ws(' ', author_subject, body, deleted_at IS NOT NULL) FROM video_comments ORDER BY created_at",
            String.class)).containsExactly("alice  t", "carol Merci f", "carol Super f");
        assertThat(send("GET", comments(video), oidc.token("dave"), null).body()).doesNotContain("Bravo").contains("Super");
    }

    static String likes(UUID video) { return "/v1/social/videos/" + video + "/likes"; }
    static String comments(UUID video) { return "/v1/social/videos/" + video + "/comments"; }
    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
