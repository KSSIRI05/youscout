package ma.youscout.user;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void setup() { client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
    @AfterAll static void cleanup() { oidc.server.stop(0); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Test void healthIsPublic() { client.get().uri("/actuator/health").exchange().expectStatus().isOk(); }
    @Test void missingTokenIsRejected() { client.get().uri("/v1/users/session").exchange().expectStatus().isUnauthorized(); }
    @Test void validSignedTokenIsAccepted() {
        client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(oidc.validToken()))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("user-service");
    }
    @Test void invalidClaimsAndSignatureAreRejected() {
        for (String token : new String[] {
            oidc.token("http://wrong-issuer", "youscout-api", Instant.now().plusSeconds(300), false),
            oidc.token(oidc.base(), "other-api", Instant.now().plusSeconds(300), false),
            oidc.token(oidc.base(), "youscout-api", Instant.now().minusSeconds(120), false),
            oidc.token(oidc.base(), "youscout-api", Instant.now().plusSeconds(300), true) }) {
            client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
        }
    }
    @Test void migrationCreatesProfilesAndIsVersioned() {
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success = true and version = '1'", Integer.class)).isEqualTo(1);
    }
    @Test void sessionIdentityComesFromVerifiedToken() {
        client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(oidc.validToken()))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.subject").isEqualTo("player-123")
            .jsonPath("$.username").isEqualTo("test-player");
    }
}
