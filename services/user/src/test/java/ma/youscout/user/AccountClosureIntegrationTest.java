package ma.youscout.user;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
/** U22 : confirmation, révocation, effacements par service (doublures), reprise après échec et anonymisation. */
// Testcontainers est déclaré avant Spring : le contexte (et son worker) se ferme avant l'arrêt de PostgreSQL.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class AccountClosureIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();
    static final String CONFIRM = "{\"confirmation\":\"FERMER\"}";
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accountStore;
    @LocalServerPort int port;
    WebTestClient client;
    final String admin = oidc.roleToken("admin"), alice = oidc.profileToken("alice", "Alice", "alice"),
        bob = oidc.profileToken("bob", "Bob", "bob"), carol = oidc.profileToken("carol", "Carol", "carol");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("youscout.keycloak-admin.base-url", oidc::base);
        r.add("youscout.keycloak-admin.client-secret", () -> "test-admin-secret");
        for (String service : List.of("video", "social", "rating", "chat", "skill"))
            r.add("youscout.account-closure." + service + "-url", () -> oidc.base() + "/" + service);
        r.add("youscout.account-closure.purge-delay", () -> "PT3S");
        r.add("youscout.account-closure.retry-base", () -> "PT0.1S");
        r.add("youscout.account-closure.poll-ms", () -> "100");
    }
    @BeforeEach void setup() {
        for (String table : List.of("account_closure_steps", "account_closures", "account_audit", "user_follows", "user_blocks"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM user_event_outbox WHERE event_type <> 'UserRelationshipsSnapshotComplete'");
        jdbc.update("DELETE FROM user_profiles");
        oidc.keycloakCalls.clear(); oidc.keycloakMissing.clear(); oidc.keycloakDown = false;
        oidc.purgeCalls.clear(); oidc.purgeFailing.clear();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        for (String token : List.of(alice, bob, carol)) get("/v1/users/me", token).expectStatus().isOk();
    }
    @AfterAll static void cleanup() { oidc.server.stop(0); }

    @Test void closingNeedsTheTypedConfirmationAndRevokesSessionsAtOnce() {
        for (String body : List.of("{}", "{\"confirmation\":\"fermer\"}", "{\"confirmation\":\"FERMER\",\"extra\":1}"))
            close(alice, body).expectStatus().isBadRequest();
        assertThat(status("alice")).isEqualTo("ACTIVE");
        assertThat(oidc.keycloakCalls).isEmpty();

        close(alice, CONFIRM).expectStatus().isEqualTo(202).expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.status").isEqualTo("IN_PROGRESS")
            .jsonPath("$.steps[0].name").isEqualTo("SESSIONS").jsonPath("$.steps[0].status").isEqualTo("DONE")
            .jsonPath("$.steps[1].name").isEqualTo("VIDEO").jsonPath("$.steps[1].status").isEqualTo("PENDING")
            .jsonPath("$.steps[6].name").isEqualTo("IDENTITY");
        assertThat(oidc.keycloakCalls).containsExactly("PUT /users/alice {\"enabled\":false}", "POST /users/alice/logout ");
        assertThat(status("alice")).isEqualTo("CLOSING");
        // Le jeton encore valide n'ouvre plus rien chez User ni, via feed-relations, chez Social, Rating et Chat.
        for (String path : List.of("/v1/users/me", "/v1/users/me/feed-relations", "/v1/users/me/following"))
            get(path, alice).expectStatus().isForbidden();
        client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isForbidden();
        get("/v1/users/alice", bob).expectStatus().isNotFound();
        client.put().uri("/v1/users/alice/follow").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isNotFound();
        client.put().uri("/v1/users/alice/block").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isNotFound();
        // Aucun effacement tant que les jetons émis avant la fermeture peuvent encore servir.
        assertThat(oidc.purgeCalls).isEmpty();

        close(alice, CONFIRM).expectStatus().isEqualTo(202);
        get("/v1/users/me/closure", alice).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("IN_PROGRESS");
        assertThat(audit("alice")).containsExactly("CLOSE_REQUESTED");
        assertThat(accountEvents()).containsExactly("alice CLOSING");
    }

    @Test void closureErasesEachServiceThenTheIdentityAndAnonymisesTheProfile() {
        client.put().uri("/v1/users/bob/follow").headers(h -> h.setBearerAuth(alice)).exchange().expectStatus().isOk();
        client.put().uri("/v1/users/alice/follow").headers(h -> h.setBearerAuth(bob)).exchange().expectStatus().isOk();
        client.put().uri("/v1/users/alice/block").headers(h -> h.setBearerAuth(carol)).exchange().expectStatus().isOk();
        jdbc.update("DELETE FROM user_event_outbox WHERE event_type <> 'UserRelationshipsSnapshotComplete'");

        close(alice, CONFIRM).expectStatus().isEqualTo(202);
        awaitCompleted("alice");

        assertThat(oidc.purgeCalls).containsExactlyInAnyOrder("VIDEO alice Bearer kc-admin-token", "SOCIAL alice Bearer kc-admin-token",
            "RATING alice Bearer kc-admin-token", "CHAT alice Bearer kc-admin-token", "SKILL alice Bearer kc-admin-token");
        assertThat(oidc.keycloakCalls).containsExactly("PUT /users/alice {\"enabled\":false}", "POST /users/alice/logout ",
            "DELETE /users/alice ");
        assertThat(jdbc.queryForList("SELECT concat_ws(' ', display_name, account_status, length(biography)) FROM user_profiles WHERE subject = 'alice'",
            String.class)).containsExactly("Compte fermé CLOSED 0");
        assertThat(jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM user_follows WHERE 'alice' IN (follower_subject, followed_subject))
                 + (SELECT count(*) FROM user_blocks WHERE 'alice' IN (blocker_subject, blocked_subject))
            """, Long.class)).isZero();
        assertThat(jdbc.queryForList("""
            SELECT concat_ws(' ', payload->'data'->>'action', payload->'data'->>'actorSubject', payload->'data'->>'targetSubject')
              FROM user_event_outbox WHERE event_type = 'UserRelationChanged' ORDER BY event_sequence
            """, String.class)).containsExactlyInAnyOrder("UNFOLLOW alice bob", "UNFOLLOW bob alice", "UNBLOCK carol alice");
        assertThat(accountEvents()).containsExactly("alice CLOSING", "alice CLOSED");
        assertThat(audit("alice")).containsExactly("CLOSE_REQUESTED", "CLOSED");

        get("/v1/users/admin/accounts/alice/closure", admin).expectStatus().isOk().expectBody()
            .jsonPath("$.status").isEqualTo("COMPLETED").jsonPath("$.completedAt").exists()
            .jsonPath("$.steps.length()").isEqualTo(7).jsonPath("$.steps[?(@.status != 'DONE')]").isEmpty();
        get("/v1/users/alice", bob).expectStatus().isNotFound();
        get("/v1/users/me", alice).expectStatus().isForbidden();
        close(alice, CONFIRM).expectStatus().isEqualTo(202).expectBody().jsonPath("$.status").isEqualTo("COMPLETED");
        assertThat(oidc.purgeCalls).hasSize(5);
    }

    @Test void aFailingServiceIsRetriedAndLoggedWithoutBlockingTheOthers() {
        oidc.purgeFailing.add("CHAT");
        close(alice, CONFIRM).expectStatus().isEqualTo(202);
        await().atMost(Duration.ofSeconds(20)).until(() -> attempts("alice", "CHAT") >= 3);

        assertThat(jdbc.queryForList("SELECT concat_ws(' ', step, status, last_error) FROM account_closure_steps WHERE subject = 'alice' AND step IN ('VIDEO', 'CHAT', 'IDENTITY')",
            String.class)).containsExactlyInAnyOrder("VIDEO DONE", "CHAT PENDING HTTP 503", "IDENTITY PENDING");
        assertThat(oidc.keycloakCalls).doesNotContain("DELETE /users/alice ");
        assertThat(status("alice")).isEqualTo("CLOSING");

        oidc.purgeFailing.clear();
        awaitCompleted("alice");
        assertThat(oidc.purgeCalls).filteredOn(call -> call.startsWith("VIDEO ")).hasSize(1);
        assertThat(oidc.purgeCalls).filteredOn(call -> call.startsWith("CHAT ")).hasSize(1);
    }

    @Test void keycloakOutageDelaysRevocationButNotTheBlock() {
        oidc.keycloakDown = true;
        close(alice, CONFIRM).expectStatus().isEqualTo(202).expectBody()
            .jsonPath("$.steps[0].status").isEqualTo("PENDING").jsonPath("$.steps[0].lastError").isEqualTo("HTTP 503 (Keycloak)");
        get("/v1/users/me", alice).expectStatus().isForbidden();
        oidc.keycloakDown = false;
        await().atMost(Duration.ofSeconds(10)).until(() -> attempts("alice", "SESSIONS") > 0
            && "DONE".equals(jdbc.queryForObject("SELECT status FROM account_closure_steps WHERE subject = 'alice' AND step = 'SESSIONS'", String.class)));
        assertThat(oidc.keycloakCalls).startsWith("PUT /users/alice {\"enabled\":false}", "POST /users/alice/logout ");
    }

    @Test void closingAccountsCannotBeReactivatedAndOnlyActiveAccountsClose() {
        close(alice, CONFIRM).expectStatus().isEqualTo(202);
        oidc.keycloakCalls.clear();
        for (String action : List.of("reactivate", "deactivate"))
            client.post().uri("/v1/users/admin/accounts/alice/" + action).headers(h -> h.setBearerAuth(admin)).exchange()
                .expectStatus().isEqualTo(409);
        assertThat(oidc.keycloakCalls).isEmpty();
        assertThat(status("alice")).isEqualTo("CLOSING");
        // Même si la fermeture arrive après la vérification du contrôleur, l'écriture sous verrou la refuse.
        assertThatThrownBy(() -> accountStore.change("alice", "DEACTIVATED", "role-test", null))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(audit("alice")).containsExactly("CLOSE_REQUESTED");

        client.post().uri("/v1/users/admin/accounts/bob/deactivate").headers(h -> h.setBearerAuth(admin)).exchange().expectStatus().isOk();
        close(bob, CONFIRM).expectStatus().isForbidden();
        close(oidc.profileToken("ghost", "Ghost", "ghost"), CONFIRM).expectStatus().isNotFound();
        close(carol, CONFIRM.replace("FERMER", "FERMER ")).expectStatus().isBadRequest();
        get("/v1/users/admin/accounts/carol/closure", admin).expectStatus().isNotFound();
        get("/v1/users/admin/accounts/alice/closure", bob).expectStatus().isForbidden();
    }

    WebTestClient.ResponseSpec close(String token, String body) {
        return client.post().uri("/v1/users/me/closure").headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }
    WebTestClient.ResponseSpec get(String path, String token) {
        return client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange();
    }
    String status(String subject) {
        return jdbc.queryForObject("SELECT account_status FROM user_profiles WHERE subject = ?", String.class, subject);
    }
    int attempts(String subject, String step) {
        return jdbc.queryForObject("SELECT attempts FROM account_closure_steps WHERE subject = ? AND step = ?", Integer.class, subject, step);
    }
    void awaitCompleted(String subject) {
        await().atMost(Duration.ofSeconds(30)).until(() -> "COMPLETED".equals(
            jdbc.queryForObject("SELECT status FROM account_closures WHERE subject = ?", String.class, subject)));
    }
    List<String> audit(String subject) {
        return jdbc.queryForList("SELECT action FROM account_audit WHERE subject = ? ORDER BY occurred_at", String.class, subject);
    }
    List<String> accountEvents() {
        return jdbc.queryForList("""
            SELECT concat_ws(' ', payload->'data'->>'subject', payload->'data'->>'status')
              FROM user_event_outbox WHERE event_type = 'UserAccountChanged' ORDER BY event_sequence
            """, String.class);
    }
}
