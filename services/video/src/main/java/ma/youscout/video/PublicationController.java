package ma.youscout.video;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class PublicationController {
    private final PublicationService publications;
    PublicationController(PublicationService publications) { this.publications = publications; }

    @PostMapping("/v1/videos/{id}/publish")
    ResponseEntity<PublicationService.Publication> publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) Map<String, Object> fields) {
        if (fields != null && !fields.isEmpty()) throw UploadInput.invalid("Aucun champ n'est attendu pour publier la vidéo.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(publications.publish(id, jwt.getSubject()));
    }
}
