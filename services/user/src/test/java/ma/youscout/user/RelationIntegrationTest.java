package ma.youscout.user;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
/** Suivis, blocages et événements d'outbox écrits dans la même transaction ; Kafka reste désactivé. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RelationIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    WebTestClient client;
    String alice, bob;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    // Conserve le marqueur de rattrapage de la migration ; seuls les événements du test sont effacés.
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM user_follows");
        jdbc.update("DELETE FROM user_blocks");
        jdbc.update("DELETE FROM user_event_outbox WHERE event_type = 'UserRelationChanged'");
        jdbc.update("DELETE FROM user_profiles");
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        alice = oidc.profileToken("alice", "Alice", "alice");
        bob = oidc.profileToken("bob", "Bob", "bob");
        for (String token : List.of(alice, bob))
            client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
    }
    @AfterAll static void cleanup() { oidc.server.stop(0); }

    @Test void followIsIdempotentAndEachChangeIsRecordedInOrder() {
        for (int attempt = 0; attempt < 2; attempt++)
            client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.following").isEqualTo(true).jsonPath("$.followerCount").isEqualTo(1);
        client.get().uri("/v1/users/bob").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.following").isEqualTo(true).jsonPath("$.followerCount").isEqualTo(1);
        client.delete().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.following").isEqualTo(false).jsonPath("$.followerCount").isEqualTo(0);
        assertThat(events()).containsExactly("FOLLOW alice bob", "FOLLOW alice bob", "UNFOLLOW alice bob");
    }

    @Test void followRejectsSelfAndUnknownProfilesWithoutEvents() {
        client.put().uri("/v1/users/alice/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isBadRequest();
        client.put().uri("/v1/users/ghost/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isNotFound();
        client.put().uri("/v1/users/bob/follow").exchange().expectStatus().isUnauthorized();
        assertThat(events()).isEmpty();
    }

    @Test void blockRemovesFollowsBothWaysAndPreventsNewOnes() {
        client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk();
        client.put().uri("/v1/users/alice/follow").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk();
        client.put().uri("/v1/users/alice/block").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.blocked").isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_follows", Integer.class)).isZero();
        client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isEqualTo(409);
        client.get().uri("/v1/users/me/feed-relations").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.blockedSubjects").isEmpty().jsonPath("$.blockedBySubjects[0]").isEqualTo("bob");
        client.get().uri("/v1/users/me/feed-relations").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.blockedSubjects[0]").isEqualTo("alice").jsonPath("$.followingSubjects").isEmpty();

        client.delete().uri("/v1/users/alice/block").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.blocked").isEqualTo(false);
        client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk();
        assertThat(events()).containsExactly("FOLLOW alice bob", "FOLLOW bob alice", "BLOCK bob alice", "UNBLOCK bob alice", "FOLLOW alice bob");
    }

    @Test void selfBlockIsRejectedWithAReadableMessage() {
        client.put().uri("/v1/users/alice/block").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isBadRequest()
            .expectBody(String.class).value(body -> assertThat(body).doesNotContain("Ã"));
        assertThat(events()).isEmpty();
    }

    List<String> events() {
        return jdbc.queryForList("""
            SELECT concat_ws(' ', payload->'data'->>'action', payload->'data'->>'actorSubject', payload->'data'->>'targetSubject')
              FROM user_event_outbox WHERE event_type = 'UserRelationChanged' ORDER BY event_sequence
            """, String.class);
    }
}
