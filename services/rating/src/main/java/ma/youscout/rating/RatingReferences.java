package ma.youscout.rating;

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
class RatingReferences {
    record PublishedVideo(String ownerSubject, Set<UUID> skillIds) {}

    private final RestClient users;
    private final RestClient videos;

    @Autowired
    RatingReferences(@Value("${rating.user-service-url:http://localhost:8081}") String userUrl,
                     @Value("${rating.video-service-url:http://localhost:8083}") String videoUrl) {
        this.users = client(userUrl);
        this.videos = client(videoUrl);
    }

    /** Propriétaire et compétences d'une vidéo publiée ; 404 si elle n'existe pas ou n'est pas publiée. */
    PublishedVideo video(UUID videoId, String token) {
        Map<?, ?> data = call(() -> videos.get().uri("/v1/videos/{id}/publication", videoId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class),
            "Vidéo publiée introuvable.", "Le service vidéo est momentanément indisponible.");
        if (data == null || !(data.get("ownerSubject") instanceof String owner) || owner.isBlank()
                || !(data.get("skillIds") instanceof List<?> ids))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le service vidéo a renvoyé une réponse invalide.");
        var skills = new HashSet<UUID>();
        try {
            for (Object id : ids) skills.add(UUID.fromString((String) id));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le service vidéo a renvoyé une réponse invalide.");
        }
        return new PublishedVideo(owner, Set.copyOf(skills));
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
