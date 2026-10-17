package ma.youscout.gateway;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests HTTP réels ; les identités sont signées par la fixture locale. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)

class SecurityIntegrationTest {
    static final OidcFixture oidc = new OidcFixture();

    @LocalServerPort int port;
    WebTestClient client;
    // Réinitialise le contexte du test pour rendre les scénarios indépendants.
    @BeforeEach void setup() { client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
    @AfterAll static void cleanup() { oidc.server.stop(0); }
    // Remplace les adresses de production par celles des ressources temporaires du test.
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("USER_SERVICE_URL", oidc::base);
        r.add("SKILL_SERVICE_URL", oidc::base);
    }
    // Les sondes restent accessibles sans jeton pour permettre la supervision.
    @Test void healthIsPublic() { client.get().uri("/actuator/health").exchange().expectStatus().isOk(); }
    // Une route protégée doit refuser un appel anonyme.
    @Test void missingTokenIsRejected() { client.get().uri("/v1/users/session").exchange().expectStatus().isUnauthorized(); }
    // Exerce la validation cryptographique avec un JWT réellement signé par la fixture.
    @Test void validSignedTokenIsAccepted() {
        client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(oidc.validToken()))
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("stub-user");
    }
    // Une signature correcte ne suffit pas : issuer, audience et expiration sont aussi contrôlés.
    @Test void invalidClaimsAndSignatureAreRejected() {
        for (String token : new String[] {
            oidc.token("http://wrong-issuer", "youscout-api", Instant.now().plusSeconds(300), false),
            oidc.token(oidc.base(), "other-api", Instant.now().plusSeconds(300), false),
            oidc.token(oidc.base(), "youscout-api", Instant.now().minusSeconds(120), false),
            oidc.token(oidc.base(), "youscout-api", Instant.now().plusSeconds(300), true) }) {
            client.get().uri("/v1/users/session").headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isUnauthorized();
        }
    }
    // Le service destinataire reçoit le même Bearer et un identifiant de requête borné.
    @Test void gatewayPreservesTokenAndCorrelationId() {
        String token = oidc.validToken();
        client.get().uri("/v1/users/session").header("X-Request-Id", "test-request-123")
            .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
            .expectHeader().valueEquals("X-Request-Id", "test-request-123");
        assertThat(oidc.authorization.get()).isEqualTo("Bearer " + token);
        assertThat(oidc.requestId.get()).isEqualTo("test-request-123");
    }
    // Teste le prévol du navigateur depuis les origines autorisées et une origine inconnue.
    @Test void corsPreflightAllowsOnlyKnownFrontend() {
        client.options().uri("/v1/users/session").header("Origin", "http://localhost:7357")
            .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:7357");
        client.options().uri("/v1/users/session").header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isForbidden();
    }

    // Sépare le simple jeton valide, le rôle user et le privilège admin.
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

    // Des claims hors realm_access et des en-têtes falsifiés ne doivent pas donner le rôle admin.
    @Test void scopesClientRolesAndHeadersCannotPromoteUserToAdmin() {
        for (String token : new String[]{oidc.roleToken("user"),
            oidc.rolesToken(java.util.Map.of("roles", "admin"), null, "admin ROLE_admin", Instant.now().plusSeconds(300)),
            oidc.rolesToken(null, java.util.Map.of("youscout-mobile", java.util.Map.of("roles", java.util.List.of("admin"))), "admin", Instant.now().plusSeconds(300)),
            oidc.rolesToken(java.util.Map.of("roles", java.util.List.of(42, "ADMIN", "super-admin")), null, null, Instant.now().plusSeconds(300))}) {
            client.get().uri("/v1/users/admin/session").header("X-User-Roles", "admin").header("X-User-Id", "administrator")
                .headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isForbidden();
        }
    }

    // Le client reçoit une erreur structurée pour distinguer 401 et 403.
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

    // Vérifie le refus par défaut des routes sans autorisation déclarée.
    @Test void newEndpointsAreDeniedUntilExplicitlyAuthorized() {
        client.get().uri("/v1/unconfigured").headers(h -> h.setBearerAuth(oidc.roleToken("admin")))
            .exchange().expectStatus().isForbidden();
    }


    // Exerce le routage du catalogue et la transmission de son jeton au serveur simulé.
    @Test void catalogueRoutesReachTheirServiceWithUserBearer() {
        String token=oidc.roleToken("user");
        for(String path:java.util.List.of("/v1/skills","/v1/hashtags","/v1/hashtags/following")) {
            client.get().uri(path).headers(h->h.setBearerAuth(token)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("stub-catalogue");
            assertThat(oidc.authorization.get()).isEqualTo("Bearer "+token);
        }
    }
    // Une lecture admin du catalogue exige le rôle admin du realm.
    @Test void catalogueAdminRoutesRequireRealmAdmin() {
        client.get().uri("/v1/skills/admin").headers(h->h.setBearerAuth(oidc.roleToken("user")))
            .exchange().expectStatus().isForbidden();
        client.get().uri("/v1/skills/admin").headers(h->h.setBearerAuth(oidc.roleToken("admin")))
            .exchange().expectStatus().isOk();
        client.get().uri("/v1/hashtags").headers(h->h.setBearerAuth(oidc.roleToken()))
            .exchange().expectStatus().isForbidden();
    }
}
