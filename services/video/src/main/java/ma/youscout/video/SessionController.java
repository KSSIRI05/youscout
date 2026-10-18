package ma.youscout.video;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Diagnostic de l'identité vérifiée ; ne crée aucune vidéo et ne renvoie jamais le jeton. */
@RestController
class SessionController {
    record SessionIdentity(String subject, String service) {}

    @GetMapping("/v1/videos/session")
    ResponseEntity<SessionIdentity> session(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionIdentity(jwt.getSubject(), "video-service"));
    }
}
