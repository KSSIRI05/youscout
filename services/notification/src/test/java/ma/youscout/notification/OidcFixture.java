package ma.youscout.notification;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Clé RSA et serveur JWKS locaux : des JWT réels sans Keycloak. */
final class OidcFixture {
    final RSAKey key;
    final HttpServer server;

    OidcFixture() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", e -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, body.length);
                e.getResponseBody().write(body);
                e.close();
            });
            server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    String token(String subject, String... roles) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(base()).audience("youscout-api").subject(subject)
                .claim("realm_access", Map.of("roles", List.of(roles.length == 0 ? new String[] {"user"} : roles)))
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
