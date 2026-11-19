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
/** Désactivation de compte : Keycloak (doublure), état du profil, audit et événement d'outbox. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountAdminIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    WebTestClient client;
    final String admin = oidc.roleToken("admin"), alice = oidc.profileToken("alice", "Alice", "alice"), bob = oidc.profileToken("bob", "Bob", "bob");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("youscout.account-closure.worker-enabled", () -> "false");
        r.add("youscout.keycloak-admin.base-url", oidc::base);
        r.add("youscout.keycloak-admin.client-secret", () -> "test-admin-secret");
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM account_audit");
        jdbc.update("DELETE FROM user_follows");
        jdbc.update("DELETE FROM user_event_outbox WHERE event_type <> 'UserRelationshipsSnapshotComplete'");
        jdbc.update("DELETE FROM user_profiles");
        oidc.keycloakCalls.clear(); oidc.keycloakMissing.clear(); oidc.keycloakDown = false;
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        for (String token : List.of(alice, bob)) client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk();
    }
    @AfterAll static void cleanup() { oidc.server.stop(0); }

    @Test void deactivationBlocksKeycloakHidesTheProfileAndIsRecordedOnce() {
        for (int attempt = 0; attempt < 2; attempt++)
            post("/v1/users/admin/accounts/alice/deactivate", admin, "{\"note\":\"Harcèlement\"}").expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.status").isEqualTo("DEACTIVATED").jsonPath("$.statusNote").isEqualTo("Harcèlement")
                .jsonPath("$.statusChangedBy").isEqualTo("role-test");
        assertThat(oidc.keycloakCalls).startsWith("PUT /users/alice {\"enabled\":false}", "POST /users/alice/logout ");
        client.get().uri("/v1/users/alice").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isNotFound();
        client.put().uri("/v1/users/alice/follow").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isNotFound();
        assertThat(jdbc.queryForList("SELECT action FROM account_audit", String.class)).containsExactly("DEACTIVATE");
        assertThat(events()).containsExactly("alice DEACTIVATED");
    }

    @Test void reactivationRestoresLoginAndProfile() {
        post("/v1/users/admin/accounts/alice/deactivate", admin, null).expectStatus().isOk();
        oidc.keycloakCalls.clear();
        post("/v1/users/admin/accounts/alice/reactivate", admin, "{}").expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");
        assertThat(oidc.keycloakCalls).containsExactly("PUT /users/alice {\"enabled\":true}");
        client.get().uri("/v1/users/alice").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk();
        assertThat(events()).containsExactly("alice DEACTIVATED", "alice ACTIVE");
        client.get().uri("/v1/users/admin/accounts/alice").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.displayName").isEqualTo("Alice").jsonPath("$.status").isEqualTo("ACTIVE");
    }

    @Test void onlyAdminsDeactivateAndFailuresLeaveTheAccountUnchanged() {
        post("/v1/users/admin/accounts/alice/deactivate", bob, null).expectStatus().isForbidden();
        post("/v1/users/admin/accounts/ghost/deactivate", admin, null).expectStatus().isNotFound();
        oidc.keycloakMissing.add("alice");
        post("/v1/users/admin/accounts/alice/deactivate", admin, null).expectStatus().isNotFound();
        oidc.keycloakMissing.clear();
        oidc.keycloakDown = true;
        post("/v1/users/admin/accounts/alice/deactivate", admin, null).expectStatus().isEqualTo(503);
        oidc.keycloakDown = false;
        for (String body : List.of("{\"note\":42}", "{\"status\":\"ACTIVE\"}", "{\"note\":\"" + "x".repeat(501) + "\"}"))
            post("/v1/users/admin/accounts/alice/deactivate", admin, body).expectStatus().isBadRequest();
        assertThat(oidc.keycloakCalls).isEmpty();
        assertThat(jdbc.queryForObject("SELECT account_status FROM user_profiles WHERE subject = 'alice'", String.class)).isEqualTo("ACTIVE");
        assertThat(events()).isEmpty();
    }

    @Test void aRevokedCachedServiceTokenIsReplacedOnce() {
        post("/v1/users/admin/accounts/alice/deactivate", admin, null).expectStatus().isOk();
        int issued = oidc.tokensIssued.get();
        oidc.rejectNextAdminCalls.set(1);
        post("/v1/users/admin/accounts/alice/reactivate", admin, null).expectStatus().isOk();
        assertThat(oidc.tokensIssued.get()).isEqualTo(issued + 1);
        assertThat(oidc.keycloakCalls).endsWith("PUT /users/alice {\"enabled\":true}");
    }

    @Test void adminsListSearchAndCountEveryAccount() {
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(oidc.profileToken("carol", "Carole_100%", "carol"))).exchange().expectStatus().isOk();
        post("/v1/users/admin/accounts/bob/deactivate", admin, null).expectStatus().isOk();
        client.get().uri("/v1/users/admin/accounts?limit=2").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.length()").isEqualTo(2).jsonPath("$[0].subject").isEqualTo("carol").jsonPath("$[1].subject").isEqualTo("bob");
        client.get().uri("/v1/users/admin/accounts?before=bob").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[*].subject").isEqualTo(List.of("alice"));
        // Les jokers SQL saisis sont cherchés tels quels.
        client.get().uri(u -> u.path("/v1/users/admin/accounts").queryParam("q", "{q}").build("_100%")).headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[*].subject").isEqualTo(List.of("carol"));
        client.get().uri(u -> u.path("/v1/users/admin/accounts").queryParam("q", "{q}").build("%")).headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[*].subject").isEqualTo(List.of("carol"));
        client.get().uri("/v1/users/admin/accounts?status=DEACTIVATED").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[*].subject").isEqualTo(List.of("bob"));
        for (String bad : List.of("status=BANNED", "limit=0", "before=ghost"))
            client.get().uri("/v1/users/admin/accounts?" + bad).headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().is4xxClientError();
        client.get().uri("/v1/users/admin/accounts/names?subject=alice&subject=ghost&subject=bob").headers(h -> h.setBearerAuth(admin))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.alice").isEqualTo("Alice").jsonPath("$.bob").isEqualTo("Bob")
            .jsonPath("$.ghost").doesNotExist();
        client.get().uri("/v1/users/admin/accounts/stats").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.accounts.ACTIVE").isEqualTo(2).jsonPath("$.accounts.DEACTIVATED").isEqualTo(1)
            .jsonPath("$.accounts.CLOSED").isEqualTo(0);
        client.get().uri("/v1/users/admin/accounts").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isForbidden();
        // Chaque création de profil publie UserProfileCreated (sujet seul), une seule fois.
        assertThat(jdbc.queryForList("""
            SELECT payload->'data'->>'subject' FROM user_event_outbox WHERE event_type = 'UserProfileCreated' ORDER BY event_sequence
            """, String.class)).containsExactly("alice", "bob", "carol");
        assertThat(jdbc.queryForList("SELECT payload->'data' FROM user_event_outbox WHERE event_type = 'UserProfileCreated'", String.class))
            .allSatisfy(data -> assertThat(data).doesNotContain("Carole"));
    }

    @Test void anAdminCannotDeactivateTheirOwnAccount() {
        client.get().uri("/v1/users/me").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk();
        post("/v1/users/admin/accounts/role-test/deactivate", admin, null).expectStatus().isBadRequest();
        assertThat(oidc.keycloakCalls).isEmpty();
    }

    WebTestClient.ResponseSpec post(String path, String token, String body) {
        var request = client.post().uri(path).headers(h -> h.setBearerAuth(token));
        return body == null ? request.exchange() : request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }
    List<String> events() {
        return jdbc.queryForList("""
            SELECT concat_ws(' ', payload->'data'->>'subject', payload->'data'->>'status')
              FROM user_event_outbox WHERE event_type = 'UserAccountChanged' ORDER BY event_sequence
            """, String.class);
    }
}
