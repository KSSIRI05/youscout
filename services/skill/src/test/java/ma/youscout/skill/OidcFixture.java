package ma.youscout.skill;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
/** Fournit une clé RSA et un serveur JWKS locaux pour tester des JWT sans dépendre de Keycloak. */
final class OidcFixture {
    final RSAKey key;
    final HttpServer server;
    final AtomicReference<String> requestId = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    // La clé privée reste dans le test ; le serveur ne publie que sa partie publique.
    OidcFixture() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", e -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
            });
            server.createContext("/v1/users/session", e -> {
                requestId.set(e.getRequestHeaders().getFirst("X-Request-Id"));
                authorization.set(e.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{\"service\":\"stub-user\"}".getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
            });
            server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String token(String issuer, String audience, Instant expiry, boolean wrongKey) {
        return token(issuer, audience, expiry, wrongKey, "player-123", null, "test-player");
    }
    String profileToken(String subject, String name, String username) {
        return token(base(), "youscout-api", Instant.now().plusSeconds(300), false, subject, name, username);
    }
    private String token(String issuer, String audience, Instant expiry, boolean wrongKey,
                         String subject, String name, String username) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .claim("name", name).claim("preferred_username", username).issueTime(Date.from(Instant.now().minusSeconds(600)))
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("user")))
                .expirationTime(Date.from(expiry)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(wrongKey ? new RSAKeyGenerator(2048).generate() : key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    // Permet de fabriquer des claims de rôles valides ou malformés pour tester les refus.
    String rolesToken(Object realm, Object clientRoles, String scope, Instant expires) {
        return rolesToken(realm,clientRoles,scope,expires,"role-test");
    }
    String rolesToken(Object realm, Object clientRoles, String scope, Instant expires, String subject) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(base()).audience("youscout-api").subject(subject)
                .claim("realm_access", realm).claim("resource_access", clientRoles).claim("scope", scope)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(expires)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    String roleToken(String... roles) {
        return rolesToken(java.util.Map.of("roles", java.util.List.of(roles)), null, null, Instant.now().plusSeconds(300));
    }
    String validToken() { return token(base(), "youscout-api", Instant.now().plusSeconds(300), false); }
}
