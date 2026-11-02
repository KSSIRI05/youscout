package ma.youscout.user;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Expose le profil du compte connecté ; aucun identifiant de propriétaire n’est fourni par le client. */
@RestController
@RequestMapping("/v1/users")
class ProfileController {
    private final ProfileService profiles;

    ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping("/{subject}")
    ResponseEntity<PublicProfile> publicProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.publicProfile(jwt, subject));
    }

    @GetMapping("/me/following")
    ResponseEntity<java.util.List<String>> following(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.following(jwt));
    }

    @GetMapping("/me/feed-relations")
    ResponseEntity<FeedRelations> feedRelations(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.feedRelations(jwt));
    }

    @PutMapping("/{subject}/follow")
    ResponseEntity<FollowResult> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.follow(jwt, subject));
    }

    @DeleteMapping("/{subject}/follow")
    ResponseEntity<FollowResult> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.unfollow(jwt, subject));
    }

    @PutMapping("/{subject}/block")
    ResponseEntity<BlockResult> block(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.block(jwt, subject));
    }

    @DeleteMapping("/{subject}/block")
    ResponseEntity<BlockResult> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.unblock(jwt, subject));
    }

    // no-store évite la conservation de données personnelles dans les caches HTTP.
    @GetMapping("/me")
    ResponseEntity<UserProfile> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.getOrCreate(jwt));
    }

    // Valide le corps avant de transmettre la modification au service métier.
    @PatchMapping(path = "/me", consumes = "application/json")
    ResponseEntity<UserProfile> patch(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> fields) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.patch(jwt, ProfilePatch.from(fields)));
    }
}
