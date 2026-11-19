package ma.youscout.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envoie chaque notification en attente aux appareils du destinataire et en garde le statut : SENT, NO_DEVICE,
 * DISABLED (push non configuré) ou FAILED. Le texte reste générique : ni nom ni contenu ne quittent YouScout.
 */
@Component
class PushDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
    private record Pending(UUID id, String recipient, String type, UUID videoId, UUID conversationId) {}
    private record Device(UUID id, String ciphertext) {}
    static final Map<String, String> TITLES = Map.of("FOLLOWED", "Nouvel abonné", "VIDEO_PUBLISHED", "Nouvelle vidéo",
        "VIDEO_LIKED", "Nouveau like", "COMMENTED", "Nouveau commentaire", "REPLIED", "Nouvelle réponse", "MESSAGE", "Nouveau message");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PushSender sender;
    private final DeviceTokens tokens;

    PushDispatcher(JdbcTemplate jdbc, PlatformTransactionManager manager, PushSender sender, DeviceTokens tokens) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.sender = sender;
        this.tokens = tokens;
    }

    @Scheduled(fixedDelayString = "${notification.push.poll-ms:2000}", initialDelayString = "3000")
    void poll() {
        try {
            for (int count = 0; count < 20 && dispatchNext(); count++) { /* lot borné */ }
        } catch (RuntimeException unavailable) {
            log.warn("Push dispatch unavailable; pending notifications will be retried.");
        }
    }

    /** Une notification à la fois, verrouillée : deux instances ne l'envoient pas deux fois. */
    boolean dispatchNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<Pending> next = jdbc.query("""
                SELECT id, recipient_subject, type, video_id, conversation_id FROM notifications
                 WHERE push_status = 'PENDING' ORDER BY updated_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (r, n) -> new Pending(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                    r.getObject(4, UUID.class), r.getObject(5, UUID.class)));
            if (next.isEmpty()) return false;
            Pending notification = next.getFirst();
            jdbc.update("UPDATE notifications SET push_status = ? WHERE id = ?", deliver(notification), notification.id());
            return true;
        }));
    }

    private String deliver(Pending notification) {
        if (!sender.enabled()) return "DISABLED";
        List<Device> devices = jdbc.query("SELECT id, token_ciphertext FROM devices WHERE subject = ?",
            (r, n) -> new Device(r.getObject(1, UUID.class), r.getString(2)), notification.recipient());
        if (devices.isEmpty()) return "NO_DEVICE";
        var data = new java.util.HashMap<String, String>(Map.of("notificationId", notification.id().toString(), "type", notification.type()));
        if (notification.videoId() != null) data.put("videoId", notification.videoId().toString());
        if (notification.conversationId() != null) data.put("conversationId", notification.conversationId().toString());
        boolean sent = false;
        for (Device device : devices) {
            switch (sender.send(tokens.decrypt(device.ciphertext()), TITLES.get(notification.type()), "Ouvre YouScout pour voir.", data)) {
                case SENT -> sent = true;
                // Jeton expiré ou désinstallé : l'appareil est oublié.
                case INVALID_TOKEN -> jdbc.update("DELETE FROM devices WHERE id = ?", device.id());
                case FAILED -> { }
            }
        }
        return sent ? "SENT" : "FAILED";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {}
}
