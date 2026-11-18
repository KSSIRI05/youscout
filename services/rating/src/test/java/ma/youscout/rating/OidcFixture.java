package ma.youscout.rating;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Clé RSA et JWKS locaux, plus des doublures des API User et Video appelées par Rating. */
final class OidcFixture {
    record Published(String owner, List<UUID> skills) {}
    final RSAKey key;
    final HttpServer server;
    final Map<String, Published> publishedVideos = new ConcurrentHashMap<>();
    final Set<List<String>> blocks = ConcurrentHashMap.newKeySet();
    volatile boolean videoUnavailable;

    OidcFixture() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", e -> json(e, 200, new JWKSet(key.toPublicJWK()).toString()));
            server.createContext("/v1/videos/", e -> {
                String id = e.getRequestURI().getPath().split("/")[3];
                Published video = publishedVideos.get(id);
                if (videoUnavailable) json(e, 500, "{}");
                else if (caller(e) == null || !e.getRequestURI().getPath().endsWith("/publication")) json(e, 401, "{}");
                else if (video == null) json(e, 404, "{}");
                else json(e, 200, "{\"id\":\"" + id + "\",\"ownerSubject\":" + quote(video.owner())
                    + ",\"publishedAt\":\"2026-11-01T00:00:00Z\",\"skillIds\":" + video.skills().stream().map(s -> quote(s.toString())).toList() + "}");
            });
            server.createContext("/v1/users/me/feed-relations", e -> {
                String me = caller(e);
                if (me == null) { json(e, 401, "{}"); return; }
                var blocked = blocks.stream().filter(b -> b.get(0).equals(me)).map(b -> quote(b.get(1))).toList();
                var blockedBy = blocks.stream().filter(b -> b.get(1).equals(me)).map(b -> quote(b.get(0))).toList();
                json(e, 200, "{\"followingSubjects\":[],\"blockedSubjects\":" + blocked + ",\"blockedBySubjects\":" + blockedBy + "}");
            });
            server.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void reset() { publishedVideos.clear(); blocks.clear(); videoUnavailable = false; }

    String token(String subject, String... roles) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(base()).audience("youscout-api").subject(subject)
                .claim("realm_access", Map.of("roles", roles.length == 0 ? List.of("user") : List.of(roles)))
                .issueTime(Date.from(Instant.now().minusSeconds(60))).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // Les doublures n'acceptent que le jeton de l'utilisateur, transmis tel quel par Rating.
    private String caller(HttpExchange e) {
        String header = e.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            var jwt = SignedJWT.parse(header.substring(7));
            return jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(key.toRSAPublicKey())) ? jwt.getJWTClaimsSet().getSubject() : null;
        } catch (Exception invalid) { return null; }
    }
    private static String quote(String value) { return "\"" + value + "\""; }
    private static void json(HttpExchange e, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
