package ma.youscout.skill;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Valide les écritures et assure leur atomicité avec le journal des actions admin. */
@Service
class CatalogService {
    private final CatalogRepository repository;
    CatalogService(CatalogRepository repository) { this.repository = repository; }
    // La création et l’audit réussissent ensemble ou sont tous deux annulés.
    @Transactional public Skill create(String actor, Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("name", "description"));
        String name = CatalogInput.name(body.get("name"));
        String description = body.containsKey("description") ? CatalogInput.text(body.get("description"), "description", 500, true) : "";
        Skill skill=repository.createSkill(name, description);
        repository.audit(skill.id(),actor,"CREATE");
        return skill;
    }
    // Ne transmet que les champs présents ; désactiver conserve la compétence en base.
    @Transactional public Skill patch(String actor, UUID id, Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("name", "description", "active"));
        String name = body.containsKey("name") ? CatalogInput.name(body.get("name")) : null;
        String description = body.containsKey("description") ? CatalogInput.text(body.get("description"), "description", 500, true) : null;
        Boolean active = null;
        if (body.containsKey("active")) {
            if (!(body.get("active") instanceof Boolean)) CatalogInput.bad("active doit être un booléen non nul.");
            active = (Boolean) body.get("active");
        }
        Skill skill=repository.patchSkill(id,name,description,active);
        repository.audit(id,actor,"UPDATE");
        return skill;
    }
    // L’INSERT puis la relecture utilisent la même transaction.
    @Transactional public EnsuredHashtag ensure(Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("name"));
        EnsuredHashtag result = repository.ensureHashtag(CatalogInput.hashtag(body.get("name")));
        // Un hashtag désactivé par un administrateur ne peut pas être recréé par un joueur.
        if (!result.hashtag().active())
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Ce hashtag a été désactivé.");
        return result;
    }
    // L'état et le journal admin changent ensemble ; un appel qui ne change rien n'est pas journalisé.
    @Transactional public Hashtag setHashtagActive(String actor, UUID id, Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("active"));
        if (!(body.get("active") instanceof Boolean active)) { CatalogInput.bad("active doit être un booléen non nul."); return null; }
        boolean before = repository.hashtag(id, true).active();
        Hashtag hashtag = repository.setHashtagActive(id, active);
        if (before != active) repository.auditHashtag(id, actor, active ? "REACTIVATE" : "DEACTIVATE");
        return hashtag;
    }
    // Vérifie aussi l’existence du hashtag au désabonnement, même si aucune ligne ne sera supprimée.
    @Transactional public void follow(String subject, UUID id, boolean enable) {
        // Suivre exige un hashtag actif ; se désabonner reste possible après une désactivation.
        repository.hashtag(id, !enable);
        if (enable) repository.follow(subject, id); else repository.unfollow(subject, id);
    }
}
