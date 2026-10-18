package ma.youscout.video;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Aucun propriétaire ni emplacement S3 n'est accepté du client, y compris pour un administrateur. */
@RestController
class CompletionController {
    private final CompletionService completions;
    CompletionController(CompletionService completions) { this.completions = completions; }

    @PostMapping("/v1/videos/{id}/complete")
    ResponseEntity<CompletionService.Confirmation> complete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) Map<String, Object> fields) {
        if (fields != null && !fields.isEmpty()) throw UploadInput.invalid("Aucun champ n'est attendu pour confirmer l'envoi.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(completions.complete(id, jwt.getSubject()));
    }
}
