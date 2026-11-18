package ma.youscout.chat;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Connexions authentifiées de cette instance, par compte ; état transitoire, jamais une source de vérité. */
@Component
class ChatSessions {
    record Connection(String id, WebSocketSession socket) {}

    private final Map<String, Set<Connection>> bySubject = new ConcurrentHashMap<>();

    void add(String subject, Connection connection) {
        bySubject.computeIfAbsent(subject, s -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    void remove(String subject, Connection connection) {
        bySubject.computeIfPresent(subject, (s, connections) -> {
            connections.remove(connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    /** Envoie la trame aux connexions locales des comptes visés ; un client absent la récupérera via l'historique. */
    void deliver(Collection<String> subjects, String skipConnection, String frame) {
        for (String subject : subjects) {
            for (Connection connection : bySubject.getOrDefault(subject, Set.of())) {
                if (connection.id().equals(skipConnection) || !connection.socket().isOpen()) continue;
                try {
                    connection.socket().sendMessage(new TextMessage(frame));
                } catch (IOException | IllegalStateException lost) {
                    // Connexion perdue ou trop lente : le client se reconnecte et reprend depuis sa dernière séquence.
                }
            }
        }
    }
}
