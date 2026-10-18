package ma.youscout.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Associe le profil au sujet du JWT et regroupe création et lecture/modification en transaction. */
@Service
class ProfileService {
    private final ProfileRepository profiles;

    ProfileService(ProfileRepository profiles) { this.profiles = profiles; }

    @Transactional
    public UserProfile getOrCreate(Jwt jwt) {
        String subject = subject(jwt);
        profiles.createIfAbsent(subject, initialName(jwt));
        return profiles.find(subject);
    }

    @Transactional
    public UserProfile patch(Jwt jwt, ProfilePatch patch) {
        String subject = subject(jwt);
        profiles.createIfAbsent(subject, initialName(jwt));
        return profiles.patch(subject, patch);
    }

    // L’identité provient du jeton vérifié, jamais d’un paramètre ou d’un en-tête X-User-Id.
    private String subject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.codePointCount(0, subject.length()) > 255
                || subject.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité du jeton invalide.");
        }
        return subject;
    }

    // À la création seulement : nom du JWT, puis pseudo, puis valeur de secours.
    // Les lectures suivantes ne remplacent pas le nom que le joueur a personnalisé.
    private String initialName(Jwt jwt) {
        for (String claim : new String[]{"name", "preferred_username"}) {
            if (jwt.getClaims().get(claim) instanceof String name && !name.isBlank() && name.indexOf('\0') < 0) {
                String clean = name.strip();
                return clean.substring(0, clean.offsetByCodePoints(0, Math.min(80, clean.codePointCount(0, clean.length()))));
            }
        }
        return "Joueur";
    }
}
