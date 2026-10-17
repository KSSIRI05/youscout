package ma.youscout.skill;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogService {
    private final CatalogRepository repository;
    CatalogService(CatalogRepository repository) { this.repository = repository; }
    @Transactional public Skill create(String actor, Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("name", "description"));
        String name = CatalogInput.name(body.get("name"));
        String description = body.containsKey("description") ? CatalogInput.text(body.get("description"), "description", 500, true) : "";
        Skill skill=repository.createSkill(name, description);
        repository.audit(skill.id(),actor,"CREATE");
        return skill;
    }
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
    @Transactional public EnsuredHashtag ensure(Map<String,Object> body) {
        CatalogInput.fields(body, Set.of("name"));
        return repository.ensureHashtag(CatalogInput.hashtag(body.get("name")));
    }
    @Transactional public void follow(String subject, UUID id, boolean enable) {
        repository.hashtag(id);
        if (enable) repository.follow(subject, id); else repository.unfollow(subject, id);
    }
}
