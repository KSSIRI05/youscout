package ma.youscout.user;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Expose le profil du compte connecté ; aucun identifiant de propriétaire n’est fourni par le client. */
@RestController
@RequestMapping("/v1/users/me")
class ProfileController {
    private final ProfileService profiles;

    ProfileController(ProfileService profiles) { this.profiles = profiles; }

    // no-store évite la conservation de données personnelles dans les caches HTTP.
    @GetMapping
    ResponseEntity<UserProfile> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.getOrCreate(jwt));
    }

    // Valide le corps avant de transmettre la modification au service métier.
    @PatchMapping(consumes = "application/json")
    ResponseEntity<UserProfile> patch(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> fields) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.patch(jwt, ProfilePatch.from(fields)));
    }
}
