package ma.youscout.user;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** A04 : consultation, désactivation et réactivation d'un compte ; routes /v1/users/admin réservées au rôle admin. */
@RestController
@RequestMapping("/v1/users/admin/accounts")
class AccountAdminController {
    record AdminAccount(String subject, String displayName, String status, Instant statusChangedAt, String statusChangedBy,
                        String statusNote, Instant createdAt) {}

    private final AccountStore accounts;
    private final KeycloakAccounts keycloak;
    private final AccountClosures closures;

    AccountAdminController(AccountStore accounts, KeycloakAccounts keycloak, AccountClosures closures) {
        this.accounts = accounts;
        this.keycloak = keycloak;
        this.closures = closures;
    }

    @GetMapping("/{subject}")
    ResponseEntity<AdminAccount> get(@PathVariable String subject) {
        return noStore(accounts.find(validate(subject)));
    }

    /** U22 : journal de fermeture d'un compte, étape par étape. */
    @GetMapping("/{subject}/closure")
    ResponseEntity<AccountClosures.Closure> closure(@PathVariable String subject) {
        return noStore(closures.find(validate(subject)));
    }

    /**
     * Keycloak est mis à jour en premier : la connexion est bloquée avant que le profil ne passe DEACTIVATED.
     * En cas d'échec intermédiaire, rejouer l'appel termine l'opération (chaque étape est idempotente).
     */
    @PostMapping("/{subject}/deactivate")
    ResponseEntity<AdminAccount> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject,
                                            @RequestBody(required = false) Map<String, Object> body) {
        // Toute l'entrée est validée avant d'appeler Keycloak : une requête refusée ne modifie aucun compte.
        String target = validate(subject), admin = jwt.getSubject(), note = note(body);
        if (target.equals(admin)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tu ne peux pas désactiver ton propre compte.");
        requireNotClosing(accounts.find(target));
        keycloak.setEnabled(target, false);
        return noStore(accounts.change(target, "DEACTIVATED", admin, note));
    }

    @PostMapping("/{subject}/reactivate")
    ResponseEntity<AdminAccount> reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject,
                                            @RequestBody(required = false) Map<String, Object> body) {
        String target = validate(subject), note = note(body);
        // Réactiver dans Keycloak un compte en cours de fermeture rouvrirait ses sessions : refusé avant tout appel.
        requireNotClosing(accounts.find(target));
        keycloak.setEnabled(target, true);
        try {
            return noStore(accounts.change(target, "ACTIVE", jwt.getSubject(), note));
        } catch (ResponseStatusException closing) {
            // Le joueur a demandé la fermeture entre la vérification et Keycloak : son compte est désactivé de nouveau.
            if (closing.getStatusCode() == HttpStatus.CONFLICT) keycloak.setEnabled(target, false);
            throw closing;
        }
    }

    private static void requireNotClosing(AdminAccount account) {
        if (account.status().equals("CLOSING") || account.status().equals("CLOSED"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce compte est fermé ou en cours de fermeture.");
    }

    private static String validate(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.indexOf('\0') >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant de compte invalide.");
        return subject;
    }

    private static String note(Map<String, Object> body) {
        if (body == null) return null;
        if (!body.keySet().stream().allMatch("note"::equals)) throw new InvalidProfileException("Seul le champ note est accepté.");
        Object value = body.get("note");
        if (value == null) return null;
        if (!(value instanceof String text) || text.strip().length() > 500 || text.indexOf('\0') >= 0)
            throw new InvalidProfileException("La note doit contenir au plus 500 caractères.");
        return text.strip().isEmpty() ? null : text.strip();
    }

    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

/** État du compte, journal et événement UserAccountChanged écrits dans la même transaction PostgreSQL. */
@Service
class AccountStore {
    private final JdbcTemplate jdbc;
    private final ProfileRepository profiles;
    private final RelationEventOutbox events;

    AccountStore(JdbcTemplate jdbc, ProfileRepository profiles, RelationEventOutbox events) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public AccountAdminController.AdminAccount find(String subject) {
        List<AccountAdminController.AdminAccount> rows = jdbc.query("""
            SELECT subject, display_name, account_status, status_changed_at, status_changed_by, status_note, created_at
              FROM user_profiles WHERE subject = ?
            """, (r, n) -> new AccountAdminController.AdminAccount(r.getString(1), r.getString(2), r.getString(3),
                r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant(), r.getString(5), r.getString(6),
                r.getTimestamp(7).toInstant()), subject);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable.");
        return rows.getFirst();
    }

    /**
     * Un appel qui ne change pas l'état ne crée ni ligne d'audit ni événement. Un compte passé en fermeture après
     * la vérification du contrôleur est refusé ici, sous le verrou, par un 409.
     */
    @Transactional
    public AccountAdminController.AdminAccount change(String subject, String status, String admin, String note) {
        // Même verrou que les relations : l'événement de compte est ordonné avec les suivis et blocages.
        profiles.lockRelationship(subject, subject);
        int changed = jdbc.update("""
            UPDATE user_profiles SET account_status = ?, status_changed_at = clock_timestamp(), status_changed_by = ?,
                   status_note = ?, updated_at = clock_timestamp()
             WHERE subject = ? AND account_status <> ? AND account_status IN ('ACTIVE', 'DEACTIVATED')
            """, status, admin, note, subject, status);
        if (changed == 0) {
            String current = profiles.accountStatus(subject);
            if ("CLOSING".equals(current) || "CLOSED".equals(current))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce compte est fermé ou en cours de fermeture.");
        }
        if (changed == 1) {
            jdbc.update("INSERT INTO account_audit(id, subject, actor_subject, action, note) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), subject, admin, status.equals("ACTIVE") ? "REACTIVATE" : "DEACTIVATE", note);
            events.appendAccount(subject, status);
        }
        return find(subject);
    }
}
