package ma.youscout.chat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** MongoDB (replica set), Redis et JWT réels ; User est remplacé par la doublure de la fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatIntegrationTest {
    @Container static final MongoDBContainer mongoDb = new MongoDBContainer("mongo:7").withReplicaSet();
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    @Autowired MongoTemplate mongo;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        properties().forEach((key, value) -> r.add(key, () -> value));
    }
    static Map<String, String> properties() {
        return Map.of(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc.base(),
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", oidc.base() + "/jwks",
            "spring.mongodb.uri", mongoDb.getReplicaSetUrl("youscout_chat"),
            "spring.data.redis.host", redis.getHost(),
            "spring.data.redis.port", String.valueOf(redis.getMappedPort(6379)),
            "chat.user-service-url", oidc.base());
    }
    @BeforeEach void clear() {
        oidc.reset();
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), ChatStore.MESSAGES);
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), ChatStore.CONVERSATIONS);
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), ChatStore.OUTBOX);
    }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void authenticationAndApplicationRoleAreRequired() throws Exception {
        var anonymous = rest("GET", "/v1/conversations", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(rest("GET", "/v1/conversations", oidc.token("alice", "offline_access"), null).statusCode()).isEqualTo(403);
    }

    @Test void messagesAreSequencedAndResendingReturnsTheOriginal() throws Exception {
        String alice = oidc.token("alice"), bob = oidc.token("bob");
        var first = post(alice, "bob", "m-1", "Salut");
        assertThat(first.statusCode()).isEqualTo(201);
        var resent = post(alice, "bob", "m-1", "Salut");
        assertThat(resent.statusCode()).isEqualTo(200);
        assertThat(read(resent, "$.id", String.class)).isEqualTo(read(first, "$.id", String.class));
        assertThat(post(alice, "bob", "m-1", "Autre texte").statusCode()).isEqualTo(409);
        assertThat(read(post(bob, "alice", "m-1", "Bonjour"), "$.seq", Integer.class)).isEqualTo(2);
        post(alice, "bob", "m-2", "Ça va ?");

        var history = rest("GET", "/v1/conversations/alice/messages", bob, null);
        assertThat(history.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(read(history, "$.messages[*].seq", List.class)).containsExactly(1, 2, 3);
        assertThat(read(history, "$.messages[*].body", List.class)).containsExactly("Salut", "Bonjour", "Ça va ?");
        assertThat(read(history, "$.lastSeq", Integer.class)).isEqualTo(3);
        assertThat(read(rest("GET", "/v1/conversations/alice/messages?afterSeq=1", bob, null), "$.messages[*].seq", List.class)).containsExactly(2, 3);
        assertThat(read(rest("GET", "/v1/conversations/alice/messages?beforeSeq=3&limit=1", bob, null), "$.messages[*].seq", List.class)).containsExactly(2);

        var delivered = rest("POST", "/v1/conversations/alice/delivered", bob, "{\"seq\":3}");
        assertThat(read(delivered, "$.changed", Boolean.class)).isTrue();
        assertThat(rest("POST", "/v1/conversations/alice/delivered", bob, "{\"seq\":4}").statusCode()).isEqualTo(400);
        assertThat(read(rest("POST", "/v1/conversations/alice/delivered", bob, "{\"seq\":1}"), "$.changed", Boolean.class)).isFalse();
        var list = rest("GET", "/v1/conversations", alice, null);
        assertThat(read(list, "$[0].peerSubject", String.class)).isEqualTo("bob");
        assertThat(read(list, "$[0].peerDeliveredSeq", Integer.class)).isEqualTo(3);
        assertThat(read(list, "$[0].lastMessage.body", String.class)).isEqualTo("Ça va ?");
        assertThat(read(rest("GET", "/v1/conversations", oidc.token("carol"), null), "$.length()", Integer.class)).isZero();
    }

    @Test void eachNewMessageRecordsOneMessageAcceptedEventWithoutItsText() throws Exception {
        String alice = oidc.token("alice"), bob = oidc.token("bob");
        post(alice, "bob", "m-1", "Secret");
        post(alice, "bob", "m-1", "Secret");
        post(bob, "alice", "m-1", "Réponse");
        var events = mongo.find(new org.springframework.data.mongodb.core.query.Query()
            .with(org.springframework.data.domain.Sort.by("createdAt")), org.bson.Document.class, ChatStore.OUTBOX);
        assertThat(events).hasSize(2);
        var first = events.getFirst().get("payload", org.bson.Document.class);
        assertThat(first.getString("eventType")).isEqualTo("MessageAccepted");
        assertThat(first.getString("producer")).isEqualTo("chat-service");
        var data = first.get("data", org.bson.Document.class);
        assertThat(data.keySet()).containsExactlyInAnyOrder("messageId", "conversationId", "seq", "senderSubject", "recipientSubject", "acceptedAt");
        assertThat(data.getString("recipientSubject")).isEqualTo("bob");
        assertThat(events.getFirst().getString("key")).isEqualTo(data.getString("conversationId"));
        assertThat(events).noneSatisfy(e -> assertThat(e.toJson()).contains("Secret"));
    }

    @Test void accountClosureBlanksTheClosedAccountsMessagesAndKeepsTheSequence() throws Exception {
        String alice = oidc.token("alice"), bob = oidc.token("bob");
        post(alice, "bob", "m-1", "Salut");
        post(bob, "alice", "m-1", "Bonjour");
        post(alice, "bob", "m-2", "À plus");
        assertThat(rest("DELETE", "/internal/accounts/alice", null, null).statusCode()).isEqualTo(401);
        assertThat(rest("DELETE", "/internal/accounts/alice", oidc.token("svc", "admin"), null).statusCode()).isEqualTo(403);
        for (int attempt = 0; attempt < 2; attempt++)
            assertThat(rest("DELETE", "/internal/accounts/alice", oidc.token("svc", "account-closure"), null).statusCode()).isEqualTo(204);

        var history = rest("GET", "/v1/conversations/alice/messages", bob, null);
        assertThat(read(history, "$.messages[*].seq", List.class)).containsExactly(1, 2, 3);
        assertThat(read(history, "$.messages[*].body", List.class)).containsExactly("", "Bonjour", "");
        assertThat(read(history, "$.messages[*].redacted", List.class)).containsExactly(true, false, true);
        var list = rest("GET", "/v1/conversations", bob, null);
        assertThat(read(list, "$[0].lastMessage.body", String.class)).isEmpty();
        assertThat(read(list, "$[0].lastMessage.redacted", Boolean.class)).isTrue();
    }

    @Test void invalidMessagesUnknownRecipientsAndBlocksAreRefused() throws Exception {
        String alice = oidc.token("alice");
        assertThat(post(alice, "alice", "m-1", "Moi").statusCode()).isEqualTo(400);
        assertThat(post(alice, "bob", "m-1", "   ").statusCode()).isEqualTo(400);
        assertThat(post(alice, "bob", "m-1", "x".repeat(2001)).statusCode()).isEqualTo(400);
        assertThat(post(alice, "bob", "id with spaces", "Salut").statusCode()).isEqualTo(400);
        assertThat(rest("POST", "/v1/conversations/bob/messages", alice, "{\"clientMessageId\":\"m-1\",\"body\":\"Salut\",\"x\":1}").statusCode()).isEqualTo(400);
        oidc.missingProfiles.add("ghost");
        assertThat(post(alice, "ghost", "m-1", "Salut").statusCode()).isEqualTo(404);
        oidc.blocks.add(List.of("bob", "alice"));
        var blocked = post(alice, "bob", "m-1", "Salut");
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(blocked.body()).contains("blocage");
        oidc.blocks.clear();
        oidc.blocks.add(List.of("alice", "carol"));
        assertThat(post(alice, "carol", "m-1", "Salut").statusCode()).isEqualTo(403);
        oidc.userUnavailable = true;
        assertThat(post(alice, "dave", "m-1", "Salut").statusCode()).isEqualTo(503);
        assertThat(mongo.getCollection(ChatStore.MESSAGES).countDocuments()).isZero();
    }

    @Test void concurrentSendsGetContiguousSequencesWithoutDuplicates() throws Exception {
        String alice = oidc.token("alice"), bob = oidc.token("bob");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 24; i++) {
                String token = i % 2 == 0 ? alice : bob, peer = i % 2 == 0 ? "bob" : "alice";
                String id = "m-" + (i % 12);
                results.add(pool.submit(() -> post(token, peer, id, "Message " + id).statusCode()));
            }
            for (var result : results) assertThat(result.get()).isIn(200, 201);
        }
        var history = rest("GET", "/v1/conversations/bob/messages?limit=200", alice, null);
        List<Integer> seqs = read(history, "$.messages[*].seq", List.class);
        // Chaque expéditeur a 12 identifiants distincts, chacun envoyé une seule fois ou renvoyé.
        assertThat(seqs).hasSize((int) mongo.getCollection(ChatStore.MESSAGES).countDocuments());
        assertThat(seqs).isEqualTo(java.util.stream.IntStream.rangeClosed(1, seqs.size()).boxed().toList());
        assertThat(seqs.size()).isLessThanOrEqualTo(24);
    }

    @Test void webSocketDeliversLiveAndAcknowledgesOnce() throws Exception {
        try (var alice = Socket.open(port); var bob = Socket.open(port)) {
            alice.auth(oidc.token("alice"));
            bob.auth(oidc.token("bob"));
            alice.send("{\"type\":\"send\",\"to\":\"bob\",\"clientMessageId\":\"w-1\",\"body\":\"En direct\"}");
            var accepted = alice.next("accepted");
            assertThat(JsonPath.<Boolean>read(accepted, "$.created")).isTrue();
            assertThat(JsonPath.<Integer>read(accepted, "$.message.seq")).isEqualTo(1);
            var received = bob.next("message");
            assertThat(JsonPath.<String>read(received, "$.message.body")).isEqualTo("En direct");
            assertThat(JsonPath.<String>read(received, "$.message.senderSubject")).isEqualTo("alice");

            // Le renvoi après une coupure reçoit l'accusé d'origine, sans nouvelle livraison.
            alice.send("{\"type\":\"send\",\"to\":\"bob\",\"clientMessageId\":\"w-1\",\"body\":\"En direct\"}");
            assertThat(JsonPath.<Boolean>read(alice.next("accepted"), "$.created")).isFalse();
            bob.send("{\"type\":\"delivered\",\"peer\":\"alice\",\"seq\":1}");
            assertThat(JsonPath.<Integer>read(alice.next("delivered"), "$.seq")).isEqualTo(1);
            bob.send("{\"type\":\"ping\"}");
            assertThat(bob.next("pong")).contains("pong");
            assertThat(bob.frames.stream().filter(f -> f.startsWith("{\"type\":\"message\"")).count()).isEqualTo(1);

            oidc.blocks.add(List.of("bob", "alice"));
            alice.send("{\"type\":\"send\",\"to\":\"bob\",\"clientMessageId\":\"w-2\",\"body\":\"Bloqué\"}");
            var refused = alice.next("error");
            assertThat(JsonPath.<Integer>read(refused, "$.status")).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused, "$.clientMessageId")).isEqualTo("w-2");
        }
    }

    @Test void webSocketRequiresAValidPlayerTokenAndClosesWhenItExpires() throws Exception {
        try (var socket = Socket.open(port)) {
            socket.send("{\"type\":\"send\",\"to\":\"bob\",\"clientMessageId\":\"w-1\",\"body\":\"Salut\"}");
            assertThat(socket.closeCode()).isEqualTo(4401);
        }
        try (var socket = Socket.open(port)) {
            socket.send("{\"type\":\"auth\",\"token\":\"not-a-jwt\"}");
            assertThat(socket.closeCode()).isEqualTo(4401);
        }
        try (var socket = Socket.open(port)) {
            socket.send("{\"type\":\"auth\",\"token\":\"" + oidc.token("alice", "offline_access") + "\"}");
            assertThat(socket.closeCode()).isEqualTo(4403);
        }
        try (var socket = Socket.open(port)) {
            socket.auth(oidc.token("alice"));
            socket.send("{\"type\":\"auth\",\"token\":\"" + oidc.token("mallory") + "\"}");
            assertThat(socket.closeCode()).isEqualTo(4403);
        }
        try (var socket = Socket.open(port)) {
            socket.auth(oidc.token("alice", 2));
            assertThat(socket.closeCode()).isEqualTo(4401);
        }
        try (var socket = Socket.open(port)) {
            // Un jeton renouvelé avant l'expiration garde la connexion ouverte.
            socket.auth(oidc.token("alice", 2));
            socket.auth(oidc.token("alice"));
            Thread.sleep(3000);
            socket.send("{\"type\":\"ping\"}");
            assertThat(socket.next("pong")).contains("pong");
        }
        assertThat(rawHandshake("http://evil.example").statusCode()).isEqualTo(403);
    }

    @Test void messagesReachConnectionsOnAnotherInstanceThroughRedis() throws Exception {
        var args = properties().entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toList();
        var arguments = new ArrayList<>(args);
        arguments.add("--server.port=0");
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(ChatApplication.class).run(arguments.toArray(String[]::new))) {
            int otherPort = Integer.parseInt(other.getEnvironment().getProperty("local.server.port"));
            try (var alice = Socket.open(port); var bob = Socket.open(otherPort)) {
                alice.auth(oidc.token("alice"));
                bob.auth(oidc.token("bob"));
                Thread.sleep(500); // abonnement Redis de la seconde instance
                alice.send("{\"type\":\"send\",\"to\":\"bob\",\"clientMessageId\":\"r-1\",\"body\":\"Via Redis\"}");
                alice.next("accepted");
                assertThat(JsonPath.<String>read(bob.next("message"), "$.message.body")).isEqualTo("Via Redis");
                // Un envoi REST est relayé de la même façon.
                post(oidc.token("bob"), "alice", "r-2", "Réponse");
                assertThat(JsonPath.<String>read(alice.next("message"), "$.message.body")).isEqualTo("Réponse");
            }
        }
    }

    /** Client WebSocket de test : conserve les trames reçues et le code de fermeture. */
    static final class Socket implements AutoCloseable, WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final List<String> frames = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        static Socket open(int port) throws Exception {
            var socket = new Socket();
            socket.ws = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/v1/chat/ws"), socket).get(5, TimeUnit.SECONDS);
            return socket;
        }
        void send(String frame) throws Exception { ws.sendText(frame, true).get(5, TimeUnit.SECONDS); }
        void auth(String token) throws Exception {
            send("{\"type\":\"auth\",\"token\":\"" + token + "\"}");
            assertThat(next("ready")).contains("ready");
        }
        String next(String type) throws Exception {
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until) {
                String frame = queue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(JsonPath.read(frame, "$.type"))) return frame;
            }
            throw new AssertionError("Aucune trame " + type + " reçue.");
        }
        int closeCode() throws Exception { return closed.get(5, TimeUnit.SECONDS); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                queue.add(frame);
                if (!frame.contains("\"pong\"")) frames.add(frame);
            }
            webSocket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }
        @Override public void close() { if (!ws.isOutputClosed()) ws.abort(); }
    }

    private HttpResponse<String> rawHandshake(String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/ws")).header("Origin", origin)
            .header("Sec-WebSocket-Version", "13").header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==").GET();
        return HttpClient.newBuilder().build().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String token, String peer, String clientId, String body) throws Exception {
        return rest("POST", "/v1/conversations/" + peer + "/messages", token,
            "{\"clientMessageId\":\"" + clientId + "\",\"body\":\"" + body + "\"}");
    }
    private static <T> T read(HttpResponse<String> response, String path, Class<T> type) {
        return JsonPath.parse(response.body()).read(path, type);
    }
    private HttpResponse<String> rest(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
