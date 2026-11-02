package ma.youscout.video;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.server.ResponseStatusException;

/** Vérifie auprès du propriétaire du catalogue et récupère les libellés canoniques. */
@Component
class CatalogReferenceClient {
    private final RestClient skill;
    @Autowired
    CatalogReferenceClient(@Value("${video.skill-service-url:http://localhost:8082}") String baseUrl) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(3));
        skill = RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
    }

    CatalogReferenceClient(RestClient skill) {
        this.skill = skill;
    }

    List<CatalogReference> skills(List<UUID> ids, String token) { return fetch(ids, "skills", token); }
    List<CatalogReference> hashtags(List<UUID> ids, String token) { return fetch(ids, "hashtags", token); }

    private List<CatalogReference> fetch(List<UUID> ids, String collection, String token) {
        return ids.stream().map(id -> {
            try {
                Map<?, ?> data = skill.get().uri("/v1/{collection}/{id}", collection, id)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class);
                if (data == null || !id.toString().equals(data.get("id"))
                        || !(data.get("name") instanceof String name) || name.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Le catalogue a renvoyé une référence invalide.");
                }
                return new CatalogReference(id, name);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une compétence ou un hashtag sélectionné n'est plus disponible.");
            } catch (ResponseStatusException rejected) {
                throw rejected;
            } catch (RestClientException unavailable) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Le catalogue est momentanément indisponible.");
            }
        }).toList();
    }
}
