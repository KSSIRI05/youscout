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
    private final RelationEventOutbox relationEvents;

    ProfileService(ProfileRepository profiles, RelationEventOutbox relationEvents) {
        this.profiles = profiles;
        this.relationEvents = relationEvents;
    }

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

    @Transactional(readOnly = true)
    public PublicProfile publicProfile(Jwt jwt, String target) {
        String viewer = subject(jwt); validateSubject(target);
        UserProfile profile = profiles.findIfPresent(target);
        if (profile == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable.");
        return new PublicProfile(profile.subject(), profile.displayName(), profile.biography(),
            profiles.followingCount(target), profiles.followerCount(target), profiles.isFollowing(viewer, target));
    }

    @Transactional(readOnly = true)
    public java.util.List<String> following(Jwt jwt) { return profiles.following(subject(jwt)); }

    @Transactional(readOnly = true)
    public FeedRelations feedRelations(Jwt jwt) {
        String viewer = subject(jwt);
        return new FeedRelations(profiles.following(viewer), profiles.blocked(viewer), profiles.blockedBy(viewer));
    }

    @Transactional
    public FollowResult follow(Jwt jwt, String target) {
        String viewer = subject(jwt); validateSubject(target);
        profiles.lockRelationship(viewer, target);
        if (viewer.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tu ne peux pas te suivre toi-même.");
        if (profiles.findIfPresent(target) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable.");
        if (profiles.isBlockedEitherWay(viewer, target)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Un blocage empêche cet abonnement.");
        profiles.follow(viewer, target);
        relationEvents.append("FOLLOW", viewer, target);
        return new FollowResult(true, profiles.followerCount(target));
    }

    @Transactional
    public FollowResult unfollow(Jwt jwt, String target) {
        String viewer = subject(jwt); validateSubject(target);
        profiles.lockRelationship(viewer, target);
        profiles.unfollow(viewer, target);
        relationEvents.append("UNFOLLOW", viewer, target);
        return new FollowResult(false, profiles.followerCount(target));
    }

    @Transactional
    public BlockResult block(Jwt jwt, String target) {
        String viewer = subject(jwt); validateSubject(target);
        if (viewer.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Self-block is not allowed.");
        profiles.lockRelationship(viewer, target);
        if (viewer.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tu ne peux pas te bloquer toi-mÃªme.");
        if (profiles.findIfPresent(target) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Profil introuvable.");
        profiles.block(viewer, target);
        relationEvents.append("BLOCK", viewer, target);
        return new BlockResult(true);
    }

    @Transactional
    public BlockResult unblock(Jwt jwt, String target) {
        String viewer = subject(jwt); validateSubject(target);
        if (viewer.equals(target)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Self-unblock is not allowed.");
        profiles.lockRelationship(viewer, target);
        profiles.unblock(viewer, target);
        relationEvents.append("UNBLOCK", viewer, target);
        return new BlockResult(false);
    }

    private void validateSubject(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.indexOf('\0') >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifiant de profil invalide.");
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
