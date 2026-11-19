package ma.youscout.user;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** U22 : le joueur ferme son propre compte ; le sujet vient du jeton, jamais de la requête. */
@RestController
@RequestMapping("/v1/users/me/closure")
class AccountClosureController {
    /** Saisie exigée par l'application après l'explication des conséquences : un clic seul ne ferme rien. */
    static final String CONFIRMATION = "FERMER";

    private final AccountClosures closures;
    private final AccountClosureWorker worker;

    AccountClosureController(AccountClosures closures, AccountClosureWorker worker) {
        this.closures = closures;
        this.worker = worker;
    }

    /** 202 : la fermeture est enregistrée et les sessions révoquées ; les effacements se poursuivent ensuite. */
    @PostMapping(consumes = "application/json")
    ResponseEntity<AccountClosures.Closure> close(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        if (body == null || body.size() != 1 || !CONFIRMATION.equals(body.get("confirmation")))
            throw new InvalidProfileException("Saisis FERMER pour confirmer la fermeture du compte.");
        String subject = jwt.getSubject();
        closures.request(subject);
        worker.revokeSessions(subject);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(closures.find(subject));
    }

    @GetMapping
    ResponseEntity<AccountClosures.Closure> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(closures.find(jwt.getSubject()));
    }
}
