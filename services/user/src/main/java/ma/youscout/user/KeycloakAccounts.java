package ma.youscout.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Active ou désactive un compte dans Keycloak avec le compte de service youscout-user-admin
 * (rôles realm-management manage-users et view-users). Une désactivation ferme aussi les sessions ouvertes.
 * Le même compte porte le rôle realm account-closure : son jeton autorise les effacements internes de U22.
 * Le secret client n'est jamais journalisé ni renvoyé.
 */
@Component
class KeycloakAccounts {
    private final RestClient keycloak;
    private final String realm, clientId, clientSecret;
    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    KeycloakAccounts(@Value("${youscout.keycloak-admin.base-url:}") String baseUrl,
                     @Value("${youscout.keycloak-admin.realm:youscout}") String realm,
                     @Value("${youscout.keycloak-admin.client-id:youscout-user-admin}") String clientId,
                     @Value("${youscout.keycloak-admin.client-secret:}") String clientSecret) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.keycloak = baseUrl.isBlank() ? null : RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    void setEnabled(String subject, boolean enabled) {
        admin(token -> {
            keycloak.put().uri("/admin/realms/{realm}/users/{id}", realm, subject).headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("enabled", enabled)).retrieve().toBodilessEntity();
            if (!enabled)
                keycloak.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, subject).headers(h -> h.setBearerAuth(token))
                        .retrieve().toBodilessEntity();
        });
    }

    /** U22 : supprime le compte d'identité ; un compte déjà absent est considéré comme supprimé. */
    void delete(String subject) {
        try {
            admin(token -> keycloak.delete().uri("/admin/realms/{realm}/users/{id}", realm, subject)
                    .headers(h -> h.setBearerAuth(token)).retrieve().toBodilessEntity());
        } catch (ResponseStatusException missing) {
            if (missing.getStatusCode() != HttpStatus.NOT_FOUND) throw missing;
        }
    }

    /** Jeton du compte de service, réutilisé jusqu'à 30 s avant son expiration. */
    String serviceToken() {
        if (keycloak == null || clientSecret.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La gestion des comptes Keycloak n'est pas configurée.");
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) return cachedToken;
        try {
            return token();
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est momentanément indisponible.");
        }
    }

    /** Oublie un jeton refusé (401) pour que l'appel suivant en demande un nouveau ; sans effet s'il a déjà changé. */
    void forgetToken(String rejected) {
        if (rejected.equals(cachedToken)) cachedToken = null;
    }

    private void admin(java.util.function.Consumer<String> calls) {
        String token = serviceToken();
        try {
            try {
                calls.accept(token);
            } catch (HttpClientErrorException.Unauthorized rejected) {
                // Jeton mis en cache révoqué (redémarrage, rotation des clés) : un seul nouvel essai avec un jeton neuf.
                forgetToken(token);
                calls.accept(serviceToken());
            }
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable dans Keycloak.");
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est momentanément indisponible.");
        }
    }

    private String token() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> response = keycloak.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Keycloak est momentanément indisponible.");
        long lifetime = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 0;
        cachedToken = token;
        cachedUntil = Instant.now().plusSeconds(Math.max(0, lifetime - 30));
        return token;
    }
}
