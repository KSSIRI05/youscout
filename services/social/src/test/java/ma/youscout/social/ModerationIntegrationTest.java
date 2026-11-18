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

/** Signalements de commentaires, file de modération et décisions journalisées sur PostgreSQL réel. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ModerationIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    final String admin = oidc.token("moderator", "admin");

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
        jdbc.update("TRUNCATE moderation_decisions, comment_reports, video_likes, video_comments, video_reports, direct_messages");
    }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void commentReportsAreOnePerAccountAndRequireAVisibleComment() throws Exception {
        UUID comment = comment(UUID.randomUUID(), "carol", "Insulte");
        for (int attempt = 0; attempt < 2; attempt++)
            assertThat(send("POST", "/v1/social/comments/" + comment + "/reports", oidc.token("alice"), "{\"reason\":\"Propos injurieux\"}").statusCode()).isEqualTo(202);
        assertThat(count("comment_reports")).isEqualTo(1);
        assertThat(send("POST", "/v1/social/comments/" + comment + "/reports", oidc.token("bob"), "{\"reason\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(send("POST", "/v1/social/comments/" + UUID.randomUUID() + "/reports", oidc.token("bob"), "{\"reason\":\"Spam\"}").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE video_comments SET deleted_at = clock_timestamp() WHERE id = ?", comment);
        assertThat(send("POST", "/v1/social/comments/" + comment + "/reports", oidc.token("bob"), "{\"reason\":\"Spam\"}").statusCode()).isEqualTo(404);
    }

    @Test void adminRoutesRequireTheAdminRole() throws Exception {
        assertThat(send("GET", "/v1/social/admin/reports", null, null).statusCode()).isEqualTo(401);
        for (String path : List.of("/v1/social/admin/reports", "/v1/social/admin/reports?type=COMMENT"))
            assertThat(send("GET", path, oidc.token("alice"), null).statusCode()).isEqualTo(403);
        UUID comment = comment(UUID.randomUUID(), "carol", "Texte");
        assertThat(send("POST", "/v1/social/admin/comments/" + comment + "/remove", oidc.token("alice"), "{}").statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM video_comments WHERE id = ?", Boolean.class, comment)).isTrue();
    }

    @Test void queueGroupsReportsByTargetWithCountsAndLatestReasons() throws Exception {
        UUID busy = UUID.randomUUID(), quiet = UUID.randomUUID();
        for (int n = 0; n < 7; n++) videoReport(busy, "user-" + n, "Motif " + n, n);
        videoReport(quiet, "user-x", "Doublon", 100);
        var page = JsonPath.parse(send("GET", "/v1/social/admin/reports?type=VIDEO", admin, null).body());
        assertThat(page.read("$.items[*].targetId", List.class)).containsExactly(quiet.toString(), busy.toString());
        assertThat(page.read("$.items[1].reportCount", Integer.class)).isEqualTo(7);
        assertThat(page.read("$.items[1].reports[*].reason", List.class)).containsExactly("Motif 6", "Motif 5", "Motif 4", "Motif 3", "Motif 2");
        assertThat(page.read("$.hasMore", Boolean.class)).isFalse();
        for (String query : List.of("?type=USER", "?status=CLOSED", "?offset=-1"))
            assertThat(send("GET", "/v1/social/admin/reports" + query, admin, null).statusCode()).isEqualTo(400);
    }

    @Test void commentQueueShowsTheCommentAndItsVideo() throws Exception {
        UUID video = UUID.randomUUID(), comment = comment(video, "carol", "Va-t'en");
        send("POST", "/v1/social/comments/" + comment + "/reports", oidc.token("alice"), "{\"reason\":\"Harcèlement\"}");
        var page = JsonPath.parse(send("GET", "/v1/social/admin/reports?type=COMMENT", admin, null).body());
        assertThat(page.read("$.items[0].targetId", String.class)).isEqualTo(comment.toString());
        assertThat(page.read("$.items[0].videoId", String.class)).isEqualTo(video.toString());
        assertThat(page.read("$.items[0].commentBody", String.class)).isEqualTo("Va-t'en");
        assertThat(page.read("$.items[0].commentAuthor", String.class)).isEqualTo("carol");
        assertThat(page.read("$.items[0].reports[0].reason", String.class)).isEqualTo("Harcèlement");
    }

    @Test void dismissingClosesOnlyOpenReportsOfTheTargetAndIsAudited() throws Exception {
        UUID video = UUID.randomUUID(), other = UUID.randomUUID();
        videoReport(video, "alice", "Faux", 0);
        videoReport(video, "bob", "Faux aussi", 1);
        videoReport(other, "carol", "Autre", 2);
        var decision = send("POST", "/v1/social/admin/reports/VIDEO/" + video + "/decision", admin, "{\"decision\":\"DISMISSED\",\"note\":\"Contenu conforme\"}");
        assertThat(decision.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(decision.body()).read("$.reportsClosed", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status || ':' || decided_by FROM video_reports WHERE video_id = ?", String.class, video))
            .containsOnly("DISMISSED:moderator");
        assertThat(jdbc.queryForObject("SELECT status FROM video_reports WHERE video_id = ?", String.class, other)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT decision || ':' || note || ':' || reports_closed FROM moderation_decisions", String.class))
            .isEqualTo("DISMISSED:Contenu conforme:2");
        assertThat(send("POST", "/v1/social/admin/reports/VIDEO/" + video + "/decision", admin, "{\"decision\":\"DISMISSED\"}").statusCode()).isEqualTo(404);
        assertThat(JsonPath.parse(send("GET", "/v1/social/admin/reports?type=VIDEO&status=DISMISSED", admin, null).body())
            .read("$.items[0].targetId", String.class)).isEqualTo(video.toString());
        for (String body : List.of("{\"decision\":\"BANNED\"}", "{}", "{\"decision\":\"DISMISSED\",\"note\":42}"))
            assertThat(send("POST", "/v1/social/admin/reports/VIDEO/" + other + "/decision", admin, body).statusCode()).isEqualTo(400);
    }

    @Test void removingACommentHidesItClosesItsReportsAndCanBeRepeated() throws Exception {
        UUID video = UUID.randomUUID(), comment = comment(video, "carol", "Spam");
        oidc.publishedVideos.put(video.toString(), "bob");
        send("POST", "/v1/social/comments/" + comment + "/reports", oidc.token("alice"), "{\"reason\":\"Spam\"}");
        var removed = send("POST", "/v1/social/admin/comments/" + comment + "/remove", admin, "{\"note\":\"Publicité\"}");
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(removed.body()).read("$.reportsClosed", Integer.class)).isEqualTo(1);
        assertThat(send("GET", "/v1/social/videos/" + video + "/comments", oidc.token("dave"), null).body()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT removed_by FROM video_comments WHERE id = ?", String.class, comment)).isEqualTo("moderator");
        assertThat(jdbc.queryForObject("SELECT status FROM comment_reports", String.class)).isEqualTo("REVIEWED");

        assertThat(send("POST", "/v1/social/admin/comments/" + comment + "/remove", admin, null).statusCode()).isEqualTo(200);
        assertThat(count("moderation_decisions")).isEqualTo(1);
        assertThat(send("POST", "/v1/social/admin/comments/" + UUID.randomUUID() + "/remove", admin, null).statusCode()).isEqualTo(404);
    }

    UUID comment(UUID video, String author, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO video_comments(id, video_id, author_subject, body, video_owner_subject) VALUES (?, ?, ?, ?, 'bob')", id, video, author, body);
        return id;
    }
    void videoReport(UUID video, String reporter, String reason, int secondsAfter) {
        jdbc.update("INSERT INTO video_reports(id, video_id, reporter_subject, reason, created_at) VALUES (?, ?, ?, ?, timestamptz '2026-11-01T10:00:00Z' + ? * interval '1 second')",
            UUID.randomUUID(), video, reporter, reason, secondsAfter);
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
