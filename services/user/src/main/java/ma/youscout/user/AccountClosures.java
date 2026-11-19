package ma.youscout.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * U22 : état et journal d'une fermeture de compte. Le compte passe en CLOSING dans la transaction de la demande ;
 * il ne devient CLOSED, anonymisé, qu'une fois chaque étape confirmée.
 */
@Service
class AccountClosures {
    /** SESSIONS bloque la connexion ; les services effacent ensuite leurs données ; IDENTITY supprime le compte Keycloak. */
    static final List<String> STEPS = List.of("SESSIONS", "VIDEO", "SOCIAL", "RATING", "CHAT", "SKILL", "IDENTITY");
    static final List<String> SERVICE_STEPS = STEPS.subList(1, STEPS.size() - 1);
    static final String CLOSED_NAME = "Compte fermé";

    record Step(String name, String status, int attempts, String lastError, Instant completedAt) {}
    record Closure(String subject, String status, Instant requestedAt, Instant completedAt, List<Step> steps) {}

    private final JdbcTemplate jdbc;
    private final ProfileRepository profiles;
    private final RelationEventOutbox events;

    AccountClosures(JdbcTemplate jdbc, ProfileRepository profiles, RelationEventOutbox events) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.events = events;
    }

    /** Un second appel ne crée rien et renvoie la fermeture déjà enregistrée. */
    @Transactional
    public Closure request(String subject) {
        // Même verrou que les relations et la désactivation : l'événement est ordonné avec eux.
        profiles.lockRelationship(subject, subject);
        String status = profiles.accountStatus(subject);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable.");
        if (status.equals("DEACTIVATED")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ce compte n'est plus actif.");
        if (status.equals("ACTIVE")) {
            jdbc.update("""
                UPDATE user_profiles SET account_status = 'CLOSING', status_changed_at = clock_timestamp(), status_changed_by = ?,
                       status_note = NULL, updated_at = clock_timestamp()
                 WHERE subject = ?
                """, subject, subject);
            // La demande révoque elle-même les sessions ; le worker ne prend le relais qu'après une minute si elle échoue.
            jdbc.update("INSERT INTO account_closures(subject, next_attempt_at) VALUES (?, clock_timestamp() + interval '1 minute')", subject);
            for (String step : STEPS) jdbc.update("INSERT INTO account_closure_steps(subject, step) VALUES (?, ?)", subject, step);
            jdbc.update("INSERT INTO account_audit(id, subject, actor_subject, action) VALUES (?, ?, ?, 'CLOSE_REQUESTED')",
                UUID.randomUUID(), subject, subject);
            events.appendAccount(subject, "CLOSING");
        }
        return find(subject);
    }

    @Transactional(readOnly = true)
    public Closure find(String subject) {
        List<Closure> rows = jdbc.query("SELECT status, requested_at, completed_at FROM account_closures WHERE subject = ?",
            (r, n) -> new Closure(subject, r.getString(1), r.getTimestamp(2).toInstant(),
                r.getTimestamp(3) == null ? null : r.getTimestamp(3).toInstant(), null), subject);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune fermeture pour ce compte.");
        List<Step> steps = jdbc.query("""
            SELECT step, status, attempts, last_error, completed_at FROM account_closure_steps WHERE subject = ?
            """, (r, n) -> new Step(r.getString(1), r.getString(2), r.getInt(3), r.getString(4),
                r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant()), subject);
        steps = steps.stream().sorted(Comparator.comparingInt(step -> STEPS.indexOf(step.name()))).toList();
        Closure closure = rows.getFirst();
        return new Closure(subject, closure.status(), closure.requestedAt(), closure.completedAt(), steps);
    }

    /** Fermetures dues, réservées une minute : une seule instance de User traite chacune à la fois. */
    @Transactional
    public List<String> claimDue(int limit) {
        return jdbc.queryForList("""
            UPDATE account_closures SET lease_until = clock_timestamp() + interval '60 seconds'
             WHERE subject IN (SELECT subject FROM account_closures
                                WHERE status = 'IN_PROGRESS' AND next_attempt_at <= clock_timestamp()
                                  AND (lease_until IS NULL OR lease_until < clock_timestamp())
                                ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING subject
            """, String.class, limit);
    }

    List<String> pendingSteps(String subject) {
        return jdbc.queryForList("SELECT step FROM account_closure_steps WHERE subject = ? AND status = 'PENDING'", String.class, subject);
    }

    int maxPendingAttempts(String subject) {
        Integer attempts = jdbc.queryForObject(
            "SELECT max(attempts) FROM account_closure_steps WHERE subject = ? AND status = 'PENDING'", Integer.class, subject);
        return attempts == null ? 0 : attempts;
    }

    /** Null tant que les sessions ne sont pas révoquées. */
    Instant purgeAfter(String subject) {
        var value = jdbc.queryForObject("SELECT purge_after FROM account_closures WHERE subject = ?", java.sql.Timestamp.class, subject);
        return value == null ? null : value.toInstant();
    }

    /** La révocation fixe le début des effacements : après expiration des jetons d'accès déjà émis. */
    @Transactional
    public void stepDone(String subject, String step, Duration purgeDelay) {
        int changed = jdbc.update("""
            UPDATE account_closure_steps SET status = 'DONE', attempts = attempts + 1, last_error = NULL, completed_at = clock_timestamp()
             WHERE subject = ? AND step = ? AND status = 'PENDING'
            """, subject, step);
        if (changed == 1 && step.equals("SESSIONS"))
            jdbc.update("UPDATE account_closures SET purge_after = clock_timestamp() + (? * interval '1 millisecond') WHERE subject = ?",
                purgeDelay.toMillis(), subject);
    }

    @Transactional
    public void stepFailed(String subject, String step, String error) {
        jdbc.update("""
            UPDATE account_closure_steps SET attempts = attempts + 1, last_error = ? WHERE subject = ? AND step = ? AND status = 'PENDING'
            """, error.length() > 200 ? error.substring(0, 200) : error, subject, step);
    }

    @Transactional
    public void reschedule(String subject, Instant next) {
        jdbc.update("UPDATE account_closures SET next_attempt_at = ?, lease_until = NULL WHERE subject = ? AND status = 'IN_PROGRESS'",
            java.sql.Timestamp.from(next), subject);
    }

    /**
     * Dernière étape locale : relations retirées avec leurs événements (Feed nettoie sa projection), profil anonymisé.
     * La ligne CLOSED, l'audit et le journal sont conservés ; ils ne contiennent plus que le sujet et des dates.
     */
    @Transactional
    public boolean complete(String subject) {
        profiles.lockRelationship(subject, subject);
        if (!pendingSteps(subject).isEmpty()
                || !"IN_PROGRESS".equals(jdbc.queryForObject("SELECT status FROM account_closures WHERE subject = ?", String.class, subject)))
            return false;
        jdbc.query("SELECT follower_subject, followed_subject FROM user_follows WHERE follower_subject = ? OR followed_subject = ?",
            (r, n) -> List.of(r.getString(1), r.getString(2)), subject, subject).forEach(pair -> {
                jdbc.update("DELETE FROM user_follows WHERE follower_subject = ? AND followed_subject = ?", pair.get(0), pair.get(1));
                events.append("UNFOLLOW", pair.get(0), pair.get(1));
            });
        jdbc.query("SELECT blocker_subject, blocked_subject FROM user_blocks WHERE blocker_subject = ? OR blocked_subject = ?",
            (r, n) -> List.of(r.getString(1), r.getString(2)), subject, subject).forEach(pair -> {
                jdbc.update("DELETE FROM user_blocks WHERE blocker_subject = ? AND blocked_subject = ?", pair.get(0), pair.get(1));
                events.append("UNBLOCK", pair.get(0), pair.get(1));
            });
        jdbc.update("""
            UPDATE user_profiles SET display_name = ?, biography = '', account_status = 'CLOSED', status_changed_at = clock_timestamp(),
                   status_changed_by = ?, status_note = NULL, updated_at = clock_timestamp()
             WHERE subject = ?
            """, CLOSED_NAME, subject, subject);
        jdbc.update("INSERT INTO account_audit(id, subject, actor_subject, action) VALUES (?, ?, ?, 'CLOSED')", UUID.randomUUID(), subject, subject);
        events.appendAccount(subject, "CLOSED");
        jdbc.update("""
            UPDATE account_closures SET status = 'COMPLETED', completed_at = clock_timestamp(), lease_until = NULL WHERE subject = ?
            """, subject);
        return true;
    }
}
