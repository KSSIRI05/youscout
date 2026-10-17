package ma.youscout.gateway;
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
final class OidcFixture {
    final RSAKey key;
    final HttpServer server;
    final AtomicReference<String> requestId = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
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
        try {
            var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("player-123")
                .claim("preferred_username", "test-player").issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(expiry)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(wrongKey ? new RSAKeyGenerator(2048).generate() : key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    String validToken() { return token(base(), "youscout-api", Instant.now().plusSeconds(300), false); }
}
