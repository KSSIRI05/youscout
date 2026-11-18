package ma.youscout.gateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import static org.assertj.core.api.Assertions.assertThat;

/** La Gateway relaie la poignée de main et les trames WebSocket de Chat, sans jeton à l'ouverture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketRouteTest {
    static final OidcFixture oidc = new OidcFixture();
    static final DisposableServer chat = HttpServer.create().host("127.0.0.1").port(0)
        .route(routes -> routes.ws("/v1/chat/ws", (in, out) -> out.sendString(in.receive().asString().map(text -> "echo:" + text))))
        .bindNow();
    @LocalServerPort int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
        r.add("CHAT_SERVICE_URL", () -> "http://127.0.0.1:" + chat.port());
    }
    @AfterAll static void cleanup() { chat.disposeNow(); oidc.server.stop(0); }

    @Test void webSocketFramesArriveAtChatAndBack() throws Exception {
        var received = new CompletableFuture<String>();
        try (var client = HttpClient.newHttpClient()) {
            WebSocket socket = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/v1/chat/ws"),
                new WebSocket.Listener() {
                    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        received.complete(data.toString());
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"ping\"}", true).get(5, TimeUnit.SECONDS);
            assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("echo:{\"type\":\"ping\"}");
            socket.abort();
        }
    }
}
