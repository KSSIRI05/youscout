package ma.youscout.user;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fait avancer les fermetures de compte : révocation Keycloak, effacement par chaque service propriétaire, suppression
 * du compte d'identité, puis anonymisation locale. Chaque étape est idempotente ; un échec est journalisé et rejoué
 * avec un délai croissant, sans bloquer les autres services.
 */
@Component
class AccountClosureWorker {
    private static final Logger log = LoggerFactory.getLogger(AccountClosureWorker.class);
    private static final Duration MAX_RETRY = Duration.ofMinutes(10);

    private final AccountClosures closures;
    private final KeycloakAccounts keycloak;
    private final Map<String, RestClient> services;
    private final Duration purgeDelay, retryBase;

    AccountClosureWorker(AccountClosures closures, KeycloakAccounts keycloak,
                         @Value("${youscout.account-closure.video-url}") String video,
                         @Value("${youscout.account-closure.social-url}") String social,
                         @Value("${youscout.account-closure.rating-url}") String rating,
                         @Value("${youscout.account-closure.chat-url}") String chat,
                         @Value("${youscout.account-closure.skill-url}") String skill,
                         @Value("${youscout.account-closure.notification-url}") String notification,
                         @Value("${youscout.account-closure.purge-delay:PT6M}") Duration purgeDelay,
                         @Value("${youscout.account-closure.retry-base:PT5S}") Duration retryBase) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        // Video retire chaque clip dans sa propre transaction : un compte avec beaucoup de vidéos demande plus de temps.
        requests.setReadTimeout(Duration.ofSeconds(30));
        this.closures = closures;
        this.keycloak = keycloak;
        this.services = Map.of("VIDEO", client(video, requests), "SOCIAL", client(social, requests), "RATING", client(rating, requests),
            "CHAT", client(chat, requests), "SKILL", client(skill, requests), "NOTIFICATION", client(notification, requests));
        this.purgeDelay = purgeDelay;
        this.retryBase = retryBase;
    }

    @Scheduled(fixedDelayString = "${youscout.account-closure.poll-ms:2000}")
    void run() {
        List<String> due;
        try {
            due = closures.claimDue(10);
        } catch (DataAccessException unavailable) {
            // Base momentanément indisponible : le prochain passage réessaie, rien n'a été réservé.
            log.warn("Account closure poll skipped: database unavailable");
            return;
        }
        for (String subject : due) {
            try {
                process(subject);
            } catch (RuntimeException unexpected) {
                log.warn("Account closure pass failed: {}", unexpected.getClass().getSimpleName());
                closures.reschedule(subject, Instant.now().plus(retryBase));
            }
        }
    }

    /**
     * Appelé dès la demande : la connexion est bloquée sans attendre le worker, qui ne reprend la fermeture
     * qu'ensuite (au début des effacements, ou après un délai si Keycloak a échoué).
     */
    void revokeSessions(String subject) {
        if (!closures.pendingSteps(subject).contains("SESSIONS")) return;
        // Ce thread ne détient pas de bail : il déplace l'échéance sans libérer celui d'un worker éventuel.
        // purge_after peut rester nul si un worker a terminé l'étape juste avant : il reprendra alors lui-même.
        Instant purgeAfter = attempt(subject, "SESSIONS", () -> disable(subject)) ? closures.purgeAfter(subject)
            : Instant.now().plus(backoff(subject));
        closures.postpone(subject, purgeAfter == null ? Instant.now() : purgeAfter);
    }

    private void process(String subject) {
        List<String> pending = closures.pendingSteps(subject);
        if (pending.contains("SESSIONS") && !leased(subject, "SESSIONS", () -> disable(subject))) {
            closures.reschedule(subject, Instant.now().plus(backoff(subject)));
            return;
        }
        // Les jetons émis avant la révocation restent valides quelques minutes : Video accepterait encore un upload.
        Instant purgeAfter = closures.purgeAfter(subject);
        if (purgeAfter != null && purgeAfter.isAfter(Instant.now())) {
            closures.reschedule(subject, purgeAfter);
            return;
        }
        boolean purged = true;
        for (String step : AccountClosures.SERVICE_STEPS)
            if (pending.contains(step) && !leased(subject, step, () -> purge(step, subject))) purged = false;
        if (purged && pending.contains("IDENTITY") && !leased(subject, "IDENTITY", () -> keycloak.delete(subject))) purged = false;
        if (!purged || !closures.complete(subject)) closures.reschedule(subject, Instant.now().plus(backoff(subject)));
    }

    /** Étape exécutée par le worker : chacune peut durer ~40 s (connexion, lecture, jeton), le bail est prolongé avant. */
    private boolean leased(String subject, String step, Runnable action) {
        closures.renewLease(subject);
        return attempt(subject, step, action);
    }

    private boolean attempt(String subject, String step, Runnable action) {
        try {
            action.run();
            closures.stepDone(subject, step, purgeDelay);
            return true;
        } catch (ResponseStatusException | RestClientException failure) {
            closures.stepFailed(subject, step, describe(failure));
            return false;
        }
    }

    private void disable(String subject) {
        try {
            keycloak.setEnabled(subject, false);
        } catch (ResponseStatusException missing) {
            // Un compte déjà absent de Keycloak n'a plus de session à révoquer.
            if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
        }
    }

    private void purge(String step, String subject) {
        String token = keycloak.serviceToken();
        try {
            services.get(step).delete().uri("/internal/accounts/{subject}", subject).headers(h -> h.setBearerAuth(token))
                .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Unauthorized rejected) {
            // Jeton révoqué ou clés changées : le prochain essai en demandera un nouveau.
            keycloak.forgetToken(token);
            throw rejected;
        }
    }

    private Duration backoff(String subject) {
        int attempts = Math.min(closures.maxPendingAttempts(subject), 16);
        Duration delay = retryBase.multipliedBy(1L << Math.max(0, attempts - 1));
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    /** Statut ou type d'erreur seulement : aucune réponse distante ni donnée personnelle dans le journal. */
    private static String describe(RuntimeException failure) {
        if (failure instanceof ResponseStatusException status) return "HTTP " + status.getStatusCode().value() + " (Keycloak)";
        if (failure instanceof HttpStatusCodeException status) return "HTTP " + status.getStatusCode().value();
        return "Service injoignable";
    }

    private static RestClient client(String baseUrl, SimpleClientHttpRequestFactory requests) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    /** Le passage périodique peut être coupé (tests sans fermeture) ; la demande révoque toujours les sessions elle-même. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "youscout.account-closure.worker-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {}
}
