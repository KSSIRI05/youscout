package ma.youscout.chat;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Protocole JSON de Chat. La première trame doit être {@code auth} avec un jeton Keycloak ; la connexion est
 * fermée (4401) sans elle, ou à l'expiration du jeton si le client n'en a pas envoyé un nouveau.
 */
@Component
class ChatSocketHandler extends TextWebSocketHandler {
    static final CloseStatus UNAUTHENTICATED = new CloseStatus(4401, "Authentification requise");
    static final CloseStatus FORBIDDEN = new CloseStatus(4403, "Droits insuffisants");
    private static final String CLIENT = "youscout.chat.client";
    private static final Duration AUTH_DEADLINE = Duration.ofSeconds(10);

    /** État d'une connexion ; le jeton courant sert aussi aux appels vers User au nom de l'expéditeur. */
    static final class Client {
        final ChatSessions.Connection connection;
        volatile Jwt jwt;
        volatile ScheduledFuture<?> deadline;
        Client(ChatSessions.Connection connection) { this.connection = connection; }
    }

    private final JwtDecoder jwts;
    private final ChatStore store;
    private final ChatRelay relay;
    private final ChatSessions sessions;
    private final JsonMapper json;
    private final RealmRoles roles = new RealmRoles();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    ChatSocketHandler(JwtDecoder jwts, ChatStore store, ChatRelay relay, ChatSessions sessions, JsonMapper json) {
        this.jwts = jwts;
        this.store = store;
        this.relay = relay;
        this.sessions = sessions;
        this.json = json;
    }

    @PreDestroy
    void stop() { timers.shutdownNow(); }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        var socket = new ConcurrentWebSocketSessionDecorator(raw, 5000, 256 * 1024);
        var client = new Client(new ChatSessions.Connection(UUID.randomUUID().toString(), socket));
        raw.getAttributes().put(CLIENT, client);
        client.deadline = timers.schedule(() -> close(client, UNAUTHENTICATED), AUTH_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage text) {
        Client client = (Client) raw.getAttributes().get(CLIENT);
        Map<?, ?> frame;
        try {
            frame = json.readValue(text.getPayload(), Map.class);
        } catch (RuntimeException invalid) {
            frame = null;
        }
        if (frame == null) { reply(client, error(null, 400, "Trame JSON invalide.")); return; }
        String type = frame.get("type") instanceof String value ? value : "";
        if ("auth".equals(type)) { authenticate(client, frame.get("token")); return; }
        Jwt jwt = client.jwt;
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())) { close(client, UNAUTHENTICATED); return; }
        String me = jwt.getSubject();
        Object clientMessageId = frame.get("clientMessageId");
        try {
            switch (type) {
                case "send" -> {
                    String to = frame.get("to") instanceof String value ? value : null;
                    var accepted = store.send(me, to, clientMessageId, frame.get("body"), jwt.getTokenValue());
                    reply(client, frame("accepted", "clientMessageId", clientMessageId, "created", accepted.created(), "message", accepted.message()));
                    if (accepted.created())
                        relay.publish(Set.of(me, to), client.connection.id(), frame("message", "message", accepted.message()));
                }
                case "delivered" -> {
                    String peer = frame.get("peer") instanceof String value ? value : null;
                    var delivered = store.markDelivered(me, peer, frame.get("seq"));
                    if (delivered.changed()) relay.publish(Set.of(peer), null,
                        frame("delivered", "conversationId", delivered.conversationId(), "peerSubject", me, "seq", delivered.deliveredSeq()));
                }
                case "ping" -> reply(client, frame("pong"));
                default -> reply(client, error(clientMessageId, 400, "Type de trame inconnu."));
            }
        } catch (ResponseStatusException refused) {
            reply(client, error(clientMessageId, refused.getStatusCode().value(), refused.getReason()));
        } catch (DataAccessException unavailable) {
            reply(client, error(clientMessageId, 503, "Messagerie temporairement indisponible."));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Client client = (Client) raw.getAttributes().get(CLIENT);
        if (client == null) return;
        if (client.deadline != null) client.deadline.cancel(false);
        if (client.jwt != null) sessions.remove(client.jwt.getSubject(), client.connection);
    }

    private void authenticate(Client client, Object token) {
        Jwt jwt;
        try {
            if (!(token instanceof String value) || value.isBlank()) throw new JwtException("missing");
            jwt = jwts.decode(value);
        } catch (JwtException invalid) {
            close(client, UNAUTHENTICATED);
            return;
        }
        boolean player = roles.convert(jwt).stream().map(GrantedAuthority::getAuthority)
            .anyMatch(role -> role.equals("ROLE_user") || role.equals("ROLE_admin"));
        Jwt previous = client.jwt;
        if (!player || jwt.getExpiresAt() == null || (previous != null && !previous.getSubject().equals(jwt.getSubject()))) {
            close(client, FORBIDDEN);
            return;
        }
        // Le renouvellement remplace le jeton et repousse la fermeture ; le compte reste le même.
        client.jwt = jwt;
        if (client.deadline != null) client.deadline.cancel(false);
        long untilExpiry = Math.max(0, Duration.between(Instant.now(), jwt.getExpiresAt()).toMillis());
        client.deadline = timers.schedule(() -> close(client, UNAUTHENTICATED), untilExpiry, TimeUnit.MILLISECONDS);
        if (previous == null) sessions.add(jwt.getSubject(), client.connection);
        reply(client, frame("ready", "subject", jwt.getSubject(), "expiresAt", jwt.getExpiresAt()));
    }

    private void reply(Client client, Map<String, Object> frame) {
        try {
            client.connection.socket().sendMessage(new TextMessage(json.writeValueAsString(frame)));
        } catch (IOException | IllegalStateException lost) {
            // Le client reprendra l'échange à sa reconnexion.
        }
    }

    private void close(Client client, CloseStatus status) {
        try {
            client.connection.socket().close(status);
        } catch (IOException ignored) {
            // Déjà fermée.
        }
    }

    private static Map<String, Object> error(Object clientMessageId, int status, String message) {
        return frame("error", "clientMessageId", clientMessageId instanceof String id ? id : null, "status", status, "error", message);
    }

    private static Map<String, Object> frame(String type, Object... fields) {
        var frame = new LinkedHashMap<String, Object>();
        frame.put("type", type);
        for (int i = 0; i < fields.length; i += 2) frame.put((String) fields[i], fields[i + 1]);
        return frame;
    }
}
