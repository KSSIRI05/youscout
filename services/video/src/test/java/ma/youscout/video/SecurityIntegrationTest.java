package ma.youscout.video;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Requêtes HTTP et vrais JWT RSA ; aucune dépendance AWS, base ou compte Keycloak. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "VIDEO_AWS_PROFILE=nonexistent-test-profile")
@Testcontainers
class SecurityIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
    }

    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void healthRemainsPublic() throws Exception {
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test void missingAndMalformedBearerAreRejectedWithProblemJson() throws Exception {
        for (String token : new String[]{null, "invalid-token"}) {
            var response = get("/v1/videos/session", token);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(response.body()).contains("\"status\":401").doesNotContain("invalid-token");
        }
    }

    @Test void userAndAdminReceiveOnlyTheirVerifiedIdentity() throws Exception {
        for (String role : new String[]{"user", "admin"}) {
            var response = get("/v1/videos/session", oidc.roleToken(role));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"subject\":\"role-test\"", "\"service\":\"video-service\"")
                    .doesNotContain("access_token", "Authorization");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
    }

    @Test void rejectsWrongIssuerAudienceExpirationAndSignature() throws Exception {
        for (String token : new String[]{
                oidc.token("https://wrong.example", "youscout-api", Instant.now().plusSeconds(300), false),
                oidc.token(oidc.base(), "another-api", Instant.now().plusSeconds(300), false),
                oidc.token(oidc.base(), "youscout-api", Instant.now().minusSeconds(120), false),
                oidc.token(oidc.base(), "youscout-api", Instant.now().plusSeconds(300), true)}) {
            assertThat(get("/v1/videos/session", token).statusCode()).isEqualTo(401);
        }
    }

    @Test void rejectsMissingOrBlankSubjectAndMissingExpiry() throws Exception {
        for (String subject : new String[]{null, "", "   "}) {
            String token = oidc.token(oidc.base(), "youscout-api", Instant.now().plusSeconds(300), false,
                    subject, null, "username-is-not-a-subject");
            assertThat(get("/v1/videos/session", token).statusCode()).isEqualTo(401);
        }
        assertThat(get("/v1/videos/session", oidc.token(oidc.base(), "youscout-api", null, false,
                "player", null, "player")).statusCode()).isEqualTo(401);
    }

    @Test void requiresApplicationRealmRole() throws Exception {
        for (String token : new String[]{oidc.roleToken(), oidc.roleToken("viewer"),
                oidc.rolesToken(Map.of("roles", "user"), null, "user ROLE_admin", Instant.now().plusSeconds(300)),
                oidc.rolesToken(null, Map.of("youscout-mobile", Map.of("roles", java.util.List.of("user"))),
                        "user", Instant.now().plusSeconds(300))}) {
            assertThat(get("/v1/videos/session", token).statusCode()).isEqualTo(403);
        }
    }

    @Test void ignoresSpoofedIdentityAndRoleHeaders() throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/videos/session"))
                .header("Authorization", "Bearer " + oidc.roleToken("user"))
                .header("X-User-Id", "another-player").header("X-User-Roles", "admin");
        var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"subject\":\"role-test\"").doesNotContain("another-player");
        assertThat(get("/v1/videos/session", oidc.roleToken()).statusCode()).isEqualTo(403);
    }

    @Test void unimplementedRoutesRemainClosedEvenToAdmin() throws Exception {
        for (String path : new String[]{"/v1/videos/uploads", "/v1/videos/admin", "/actuator/env"}) {
            assertThat(get(path, oidc.roleToken("admin")).statusCode()).isEqualTo(403);
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
