package ma.youscout.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Protocole FCM HTTP v1 contre une doublure : assertion signée, jeton réutilisé, statuts d'erreur. */
class PushSenderTest {
    @TempDir Path dir;
    HttpServer server;
    final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();
    final AtomicInteger tokenRequests = new AtomicInteger();
    com.nimbusds.jose.jwk.RSAKey key;
    PushSender sender;

    @BeforeEach void start() throws Exception {
        key = new RSAKeyGenerator(2048).generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", e -> {
            String form = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String assertion = URLDecoder.decode(form.replaceAll(".*assertion=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            boolean valid;
            try {
                var jwt = SignedJWT.parse(assertion);
                valid = jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())) && "svc@test.iam".equals(jwt.getJWTClaimsSet().getIssuer())
                    && "https://www.googleapis.com/auth/firebase.messaging".equals(jwt.getJWTClaimsSet().getStringClaim("scope"))
                    && form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
            } catch (Exception invalid) { valid = false; }
            tokenRequests.incrementAndGet();
            byte[] body = (valid ? "{\"access_token\":\"fcm-access\",\"expires_in\":3600}" : "{}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(valid ? 200 : 401, body.length); e.getResponseBody().write(body); e.close();
        });
        server.createContext("/v1/projects/youscout-test/messages:send", e -> {
            @SuppressWarnings("unchecked") Map<String, Object> message = (Map<String, Object>) JsonMapper.builder().build().readValue(e.getRequestBody().readAllBytes(), Map.class).get("message");
            boolean authorized = "Bearer fcm-access".equals(e.getRequestHeaders().getFirst("Authorization"));
            int status = !authorized ? 401 : switch (String.valueOf(message.get("token"))) {
                case "gone" -> 404;
                case "malformed" -> 400;
                case "busy" -> 503;
                default -> 200;
            };
            if (status == 200) messages.add(message);
            byte[] body = (status == 400 ? "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}" : "{}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(status, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.toPrivateKey().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Path credentials = dir.resolve("service-account.json");
        Files.writeString(credentials, JsonMapper.builder().build().writeValueAsString(Map.of("type", "service_account",
            "client_email", "svc@test.iam", "private_key", pem, "token_uri", base + "/token")));
        sender = new PushSender("youscout-test", credentials.toString(), base);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsTheGenericNotificationWithAReusedAccessToken() {
        assertThat(sender.enabled()).isTrue();
        assertThat(sender.send("device-1", "Nouveau like", "Ouvre YouScout pour voir.", Map.of("type", "VIDEO_LIKED"))).isEqualTo(PushSender.Result.SENT);
        assertThat(sender.send("device-2", "Nouveau like", "Ouvre YouScout pour voir.", Map.of())).isEqualTo(PushSender.Result.SENT);
        assertThat(tokenRequests).hasValue(1);
        assertThat(messages.getFirst()).containsEntry("token", "device-1")
            .containsEntry("notification", Map.of("title", "Nouveau like", "body", "Ouvre YouScout pour voir."))
            .containsEntry("data", Map.of("type", "VIDEO_LIKED"));
    }

    @Test void classifiesStaleAndFailedDeliveries() {
        assertThat(sender.send("gone", "t", "b", Map.of())).isEqualTo(PushSender.Result.INVALID_TOKEN);
        assertThat(sender.send("malformed", "t", "b", Map.of())).isEqualTo(PushSender.Result.INVALID_TOKEN);
        assertThat(sender.send("busy", "t", "b", Map.of())).isEqualTo(PushSender.Result.FAILED);
    }

    @Test void staysDisabledWithoutCredentials() {
        assertThat(new PushSender("", "", "http://unused").enabled()).isFalse();
        assertThat(new PushSender("youscout-test", "", "http://unused").enabled()).isFalse();
    }
}
