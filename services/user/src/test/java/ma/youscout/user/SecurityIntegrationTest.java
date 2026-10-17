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
    @BeforeEach void setup() { jdbc.update("DELETE FROM user_profiles"); client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
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

    @Test void profileEndpointsRejectAnonymousRequestsWithoutWriting() {
        client.get().uri("/v1/users/me").exchange().expectStatus().isUnauthorized();
        client.patch().uri("/v1/users/me").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"Intrus\"}").exchange().expectStatus().isUnauthorized();
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isZero();
    }

    @Test void firstReadCreatesProfileAndRepeatedReadPreservesIt() {
        String token = oidc.profileToken("owner-a", "Yassine Amrani", "yassine");
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store").expectBody()
            .jsonPath("$.subject").isEqualTo("owner-a").jsonPath("$.displayName").isEqualTo("Yassine Amrani")
            .jsonPath("$.biography").isEqualTo("").jsonPath("$.createdAt").isNotEmpty().jsonPath("$.updatedAt").isNotEmpty();
        var original = jdbc.queryForMap("select * from user_profiles where subject = 'owner-a'");
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(oidc.profileToken("owner-a", "Nouveau nom Keycloak", "changed")))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.displayName").isEqualTo("Yassine Amrani");
        assertThat(jdbc.queryForMap("select * from user_profiles where subject = 'owner-a'")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isEqualTo(1);
    }

    @Test void partialPatchPersistsAndEmptyBiographyClearsIt() {
        String token = oidc.profileToken("editor", "Initial", "editor");
        patch(token, "{\"displayName\":\"  Yassine  \",\"biography\":\"Ailier à Casablanca ⚽\"}")
            .expectStatus().isOk().expectBody().jsonPath("$.displayName").isEqualTo("Yassine")
            .jsonPath("$.biography").isEqualTo("Ailier à Casablanca ⚽");
        var created = jdbc.queryForObject("select created_at from user_profiles where subject = 'editor'", java.sql.Timestamp.class);
        patch(token, "{\"biography\":\"Nouvelle bio\"}").expectStatus().isOk().expectBody()
            .jsonPath("$.displayName").isEqualTo("Yassine").jsonPath("$.biography").isEqualTo("Nouvelle bio");
        patch(token, "{\"displayName\":\"Yassine A.\"}").expectStatus().isOk().expectBody()
            .jsonPath("$.biography").isEqualTo("Nouvelle bio");
        patch(token, "{\"biography\":\"\"}").expectStatus().isOk();
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.displayName").isEqualTo("Yassine A.").jsonPath("$.biography").isEqualTo("");
        assertThat(jdbc.queryForObject("select created_at from user_profiles where subject = 'editor'", java.sql.Timestamp.class)).isEqualTo(created);
        assertThat(jdbc.queryForObject("select updated_at > created_at from user_profiles where subject = 'editor'", Boolean.class)).isTrue();
    }

    @Test void tokenOwnerCannotSelectOrOverwriteAnotherProfile() {
        String alice = oidc.profileToken("alice", "Alice", "alice");
        String bob = oidc.profileToken("bob", "Bob", "bob");
        patch(alice, "{\"biography\":\"Bio Alice\"}").expectStatus().isOk();
        client.patch().uri("/v1/users/me?subject=alice").header("X-User-Id", "alice")
            .headers(h -> h.setBearerAuth(bob)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"displayName\":\"Bob modifié\"}").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.subject").isEqualTo("bob");
        patch(bob, "{\"subject\":\"alice\",\"displayName\":\"Intrus\"}").expectStatus().isBadRequest();
        client.get().uri("/v1/users/me?subject=alice").header("X-User-Id", "alice").headers(h -> h.setBearerAuth(bob))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.subject").isEqualTo("bob");
        assertThat(jdbc.queryForObject("select biography from user_profiles where subject = 'alice'", String.class)).isEqualTo("Bio Alice");
        assertThat(jdbc.queryForObject("select display_name from user_profiles where subject = 'alice'", String.class)).isEqualTo("Alice");
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isEqualTo(2);
    }

    @Test void invalidPatchIsStructuredAndDoesNotModifyTheDatabase() {
        String token = oidc.profileToken("invalid", "Initial", "invalid");
        patch(token, "{\"biography\":\"Préservée\"}").expectStatus().isOk();
        var before = jdbc.queryForMap("select * from user_profiles where subject = 'invalid'");
        for (String body : new String[]{"{}", "null", "[]", "{", "{\"displayName\":null}",
            "{\"displayName\":123}", "{\"displayName\":\"   \"}", "{\"biography\":null}",
            "{\"biography\":false}", "{\"subject\":\"other\"}", "{\"createdAt\":\"now\"}",
            "{\"biography\":\"\\u0000\"}", "{\"displayName\":\"" + "a".repeat(81) + "\"}",
            "{\"biography\":\"" + "a".repeat(501) + "\"}"}) {
            patch(token, body).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(400).jsonPath("$.detail").isNotEmpty();
        }
        assertThat(jdbc.queryForMap("select * from user_profiles where subject = 'invalid'")).isEqualTo(before);
    }

    @Test void invalidFirstPatchDoesNotCreateAProfile() {
        patch(oidc.profileToken("not-created", "Initial", null), "{\"displayName\":\"\"}").expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isZero();
    }

    @Test void unicodeLimitsCountCodePointsRatherThanJavaCodeUnits() {
        String token = oidc.profileToken("unicode", "Initial", null);
        patch(token, "{\"displayName\":\"" + "⚽".repeat(80) + "\",\"biography\":\"" + "😀".repeat(500) + "\"}")
            .expectStatus().isOk();
        assertThat(jdbc.queryForObject("select char_length(biography) from user_profiles where subject = 'unicode'", Integer.class)).isEqualTo(500);
        patch(token, "{\"biography\":\"" + "😀".repeat(501) + "\"}").expectStatus().isBadRequest();
    }

    @Test void defaultNameFallsBackAndLongClaimsAreBounded() {
        for (String[] values : new String[][]{{"fallback-user", null, "pseudo", "pseudo"},
            {"fallback-default", "   ", null, "Joueur"}, {"long-name", "😀".repeat(81), null, "😀".repeat(80)}}) {
            client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(oidc.profileToken(values[0], values[1], values[2])))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.displayName").isEqualTo(values[3]);
        }
    }

    @Test void missingOrInvalidSubjectCannotCreateOrPatchAProfile() {
        for (String subject : new String[]{null, " ", "a".repeat(256)}) {
            String token = oidc.profileToken(subject, "No owner", "unknown");
            client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
            patch(token, "{\"biography\":\"Invalid\"}").expectStatus().isUnauthorized();
        }
        assertThat(jdbc.queryForObject("select count(*) from user_profiles", Integer.class)).isZero();
    }

    @Test void simultaneousFirstReadsCreateExactlyOneProfile() throws Exception {
        String token = oidc.profileToken("concurrent", "Concurrent", null);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var work = new java.util.ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 12; i++) work.add(() -> client.get().uri("/v1/users/me")
                .headers(h -> h.setBearerAuth(token)).exchange().expectBody().returnResult().getStatus().value());
            for (var result : pool.invokeAll(work)) assertThat(result.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from user_profiles where subject = 'concurrent'", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentPartialPatchesPreserveBothFields() throws Exception {
        String token = oidc.profileToken("parallel-patch", "Initial", null);
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> patch(token, "{\"displayName\":\"Nouveau nom\"}").expectBody().returnResult().getStatus().value());
            var b = pool.submit(() -> patch(token, "{\"biography\":\"Nouvelle bio\"}").expectBody().returnResult().getStatus().value());
            assertThat(a.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.displayName").isEqualTo("Nouveau nom").jsonPath("$.biography").isEqualTo("Nouvelle bio");
    }

    private WebTestClient.ResponseSpec patch(String token, String body) {
        return client.patch().uri("/v1/users/me").headers(h -> h.setBearerAuth(token))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }


    @Test void profileRequiresApplicationRoleAndAdminDiagnosticRequiresAdmin() {
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(oidc.roleToken()))
            .exchange().expectStatus().isForbidden().expectHeader()
            .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.status").isEqualTo(403).jsonPath("$.detail").isNotEmpty();
        client.get().uri("/v1/users/admin/session").headers(h -> h.setBearerAuth(oidc.roleToken("user")))
            .exchange().expectStatus().isForbidden();
        client.get().uri("/v1/users/admin/session").headers(h -> h.setBearerAuth(oidc.roleToken("admin")))
            .exchange().expectStatus().isOk();
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(oidc.roleToken("admin")))
            .exchange().expectStatus().isOk();
    }

    @Test void scopesClientRolesAndHeadersCannotPromoteUserToAdmin() {
        for (String token : new String[]{oidc.roleToken("user"),
            oidc.rolesToken(java.util.Map.of("roles", "admin"), null, "admin ROLE_admin", Instant.now().plusSeconds(300)),
            oidc.rolesToken(null, java.util.Map.of("youscout-mobile", java.util.Map.of("roles", java.util.List.of("admin"))), "admin", Instant.now().plusSeconds(300)),
            oidc.rolesToken(java.util.Map.of("roles", java.util.List.of(42, "ADMIN", "super-admin")), null, null, Instant.now().plusSeconds(300))}) {
            client.get().uri("/v1/users/admin/session").header("X-User-Roles", "admin").header("X-User-Id", "administrator")
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
        }
    }

    @Test void unauthorizedAndExpiredTokensProduceProblemResponses() {
        client.get().uri("/v1/users/me").exchange().expectStatus().isUnauthorized()
            .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
            .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.status").isEqualTo(401).jsonPath("$.detail").isNotEmpty();
        String expiredAdmin = oidc.rolesToken(java.util.Map.of("roles", java.util.List.of("admin")), null, null, Instant.now().minusSeconds(120));
        client.get().uri("/v1/users/admin/session").headers(h -> h.setBearerAuth(expiredAdmin))
            .exchange().expectStatus().isUnauthorized().expectHeader().valueEquals("WWW-Authenticate", "Bearer error=\"invalid_token\"")
            .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    @Test void newEndpointsAreDeniedUntilExplicitlyAuthorized() {
        client.get().uri("/v1/unconfigured").headers(h -> h.setBearerAuth(oidc.roleToken("admin")))
            .exchange().expectStatus().isForbidden();
    }

}
