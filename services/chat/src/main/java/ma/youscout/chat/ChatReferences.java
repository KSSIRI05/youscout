package ma.youscout.chat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Interroge User, propriétaire des profils et des blocages, avec le jeton de l'expéditeur. */
@Component
class ChatReferences {
    private final RestClient users;

    ChatReferences(@Value("${chat.user-service-url:http://localhost:8081}") String userUrl) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(3));
        this.users = RestClient.builder().baseUrl(userUrl).requestFactory(requests).build();
    }

    /** Vérifie que le destinataire a un profil ; un compte désactivé n'en expose plus. */
    void requireProfile(String subject, String token) {
        call(() -> users.get().uri("/v1/users/{subject}", subject)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity(),
            "Destinataire introuvable.");
    }

    /** Comptes bloqués par l'appelant ou qui l'ont bloqué. */
    Set<String> blockedEitherWay(String token) {
        Map<?, ?> data = call(() -> users.get().uri("/v1/users/me/feed-relations")
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class),
            "Profil introuvable.");
        if (data == null || !(data.get("blockedSubjects") instanceof List<?> blocked)
                || !(data.get("blockedBySubjects") instanceof List<?> blockedBy))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le service utilisateur a renvoyé une réponse invalide.");
        var subjects = new HashSet<String>();
        for (Object subject : blocked) if (subject instanceof String value) subjects.add(value);
        for (Object subject : blockedBy) if (subject instanceof String value) subjects.add(value);
        return subjects;
    }

    private static <T> T call(java.util.function.Supplier<T> request, String missing) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing);
        } catch (RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Le service utilisateur est momentanément indisponible.");
        }
    }
}
