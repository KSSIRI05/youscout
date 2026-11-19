package ma.youscout.social;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * U22 : efface les likes d'un compte fermé, vide et masque ses commentaires, supprime ses anciens messages directs.
 * Les signalements qu'il a déposés restent dans la file de modération, rattachés à son seul identifiant.
 * Appelée par User avec son compte de service (rôle account-closure) ; aucune route Gateway n'y mène. Rejouable.
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
        SocialController.validateSubject(subject);
        jdbc.update("DELETE FROM video_likes WHERE user_subject = ?", subject);
        // Un commentaire masqué garde sa ligne : ses réponses et signalements y restent rattachés.
        jdbc.update("""
            UPDATE video_comments SET body = '', deleted_at = COALESCE(deleted_at, clock_timestamp())
             WHERE author_subject = ? AND (body <> '' OR deleted_at IS NULL)
            """, subject);
        jdbc.update("DELETE FROM direct_messages WHERE sender_subject = ? OR recipient_subject = ?", subject, subject);
        // Les événements déjà publiés n'ont plus d'usage ; ceux en attente partent encore, Notification les ignore.
        jdbc.update("""
            DELETE FROM social_event_outbox WHERE published_at IS NOT NULL
               AND ? IN (payload->'data'->>'actorSubject', payload->'data'->>'authorSubject')
            """, subject);
        return ResponseEntity.noContent().build();
    }
}
