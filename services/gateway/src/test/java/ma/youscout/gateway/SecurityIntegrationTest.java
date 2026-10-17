package ma.youscout.gateway;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)

class SecurityIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();

    @LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void setup() { client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
    @AfterAll static void cleanup() { oidc.server.stop(0); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("USER_SERVICE_URL", oidc::base);
    }
    @Test void healthIsPublic() { client.get().uri("/actuator/health").exchange().expectStatus().isOk(); }
    @Test void missingTokenIsRejected() { client.get().uri("/v1/users/session").exchange().expectStatus().isUnauthorized(); }
    @Test void validSignedTokenIsAccepted() {
        client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(oidc.validToken()))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("stub-user");
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
    @Test void gatewayPreservesTokenAndCorrelationId() {
        String token = oidc.validToken();
        client.get().uri("/v1/users/session").header("X-Request-Id", "test-request-123")
            .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectHeader().valueEquals("X-Request-Id", "test-request-123");
        assertThat(oidc.authorization.get()).isEqualTo("Bearer " + token);
        assertThat(oidc.requestId.get()).isEqualTo("test-request-123");
    }
    @Test void corsPreflightAllowsOnlyKnownFrontend() {
        client.options().uri("/v1/users/session").header("Origin", "http://localhost:7357")
            .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:7357");
        client.options().uri("/v1/users/session").header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isForbidden();
    }
}
