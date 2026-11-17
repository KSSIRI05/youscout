package ma.youscout.social;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Interroge Video et User, propriétaires des publications et des blocages, avec le jeton de l'appelant. */
@Component
class SocialReferences {
    private final RestClient users;
    private final RestClient videos;

    @Autowired
    SocialReferences(@Value("${social.user-service-url:http://localhost:8081}") String userUrl,
                     @Value("${social.video-service-url:http://localhost:8083}") String videoUrl) {
        this(client(userUrl), client(videoUrl));
    }

    SocialReferences(RestClient users, RestClient videos) {
        this.users = users;
        this.videos = videos;
    }

    /** Renvoie le propriétaire d'une vidéo publiée ; 404 si elle n'existe pas ou n'est pas publiée. */
    String videoOwner(UUID videoId, String token) {
        Map<?, ?> data = call(() -> videos.get().uri("/v1/videos/{id}/publication", videoId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class),
            "Vidéo publiée introuvable.", "Le service vidéo est momentanément indisponible.");
        if (data == null || !(data.get("ownerSubject") instanceof String owner) || owner.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le service vidéo a renvoyé une réponse invalide.");
        return owner;
    }

    /** Vérifie que le profil existe auprès de User. */
    void requireProfile(String subject, String token) {
        call(() -> users.get().uri("/v1/users/{subject}", subject)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity(),
            "Profil introuvable.", "Le service utilisateur est momentanément indisponible.");
    }

    /** Comptes bloqués par l'appelant ou qui l'ont bloqué. */
    Set<String> blockedEitherWay(String token) {
        Map<?, ?> data = call(() -> users.get().uri("/v1/users/me/feed-relations")
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class),
            "Profil introuvable.", "Le service utilisateur est momentanément indisponible.");
        if (data == null || !(data.get("blockedSubjects") instanceof List<?> blocked)
                || !(data.get("blockedBySubjects") instanceof List<?> blockedBy))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le service utilisateur a renvoyé une réponse invalide.");
        var subjects = new HashSet<String>();
        for (Object subject : blocked) if (subject instanceof String value) subjects.add(value);
        for (Object subject : blockedBy) if (subject instanceof String value) subjects.add(value);
        return subjects;
    }

    private static <T> T call(java.util.function.Supplier<T> request, String missing, String unavailable) {
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing);
        } catch (RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable);
        }
    }

    private static RestClient client(String baseUrl) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }
}
