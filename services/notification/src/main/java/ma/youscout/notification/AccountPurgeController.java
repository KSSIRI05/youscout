package ma.youscout.notification;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * U22 : efface les notifications reçues et provoquées par un compte fermé, ses appareils et ses relations projetées.
 * Le compte reste marqué inactif : un événement tardif ne recrée rien. Appelée par User (rôle account-closure). Rejouable.
 */
@RestController
class AccountPurgeController {
    private final JdbcTemplate jdbc;

    AccountPurgeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @DeleteMapping("/internal/accounts/{subject}")
    @Transactional
    public ResponseEntity<Void> purge(@PathVariable String subject) {
        String account;
        try {
            account = NotificationIngestion.subject(subject);
        } catch (NotificationIngestion.InvalidEvent invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant de compte invalide.");
        }
        jdbc.update("INSERT INTO inactive_accounts VALUES (?) ON CONFLICT DO NOTHING", account);
        jdbc.update("DELETE FROM notifications WHERE ? IN (recipient_subject, actor_subject)", account);
        jdbc.update("DELETE FROM devices WHERE subject = ?", account);
        jdbc.update("DELETE FROM relation_follows WHERE ? IN (follower_subject, followed_subject)", account);
        jdbc.update("DELETE FROM relation_blocks WHERE ? IN (blocker_subject, blocked_subject)", account);
        return ResponseEntity.noContent().build();
    }
}
