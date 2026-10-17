package ma.youscout.user;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
/** Diagnostic d'authentification uniquement ; aucun profil n'est créé. */
@RestController
class SessionController {
    record SessionIdentity(String subject, String username, String service) {}
    @GetMapping("/v1/users/session")
    SessionIdentity session(@AuthenticationPrincipal Jwt jwt) {
        return new SessionIdentity(jwt.getSubject(), jwt.getClaimAsString("preferred_username"), "user-service");
    }
}
