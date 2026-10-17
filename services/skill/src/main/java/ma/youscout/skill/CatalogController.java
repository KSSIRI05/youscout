package ma.youscout.skill;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class CatalogController {
    private final CatalogRepository repository;
    private final CatalogService service;
    CatalogController(CatalogRepository repository, CatalogService service) { this.repository=repository; this.service=service; }
    @GetMapping("/v1/skills")
    CatalogPage<Skill> skills(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        CatalogInput.page(limit,offset); return new CatalogPage<>(repository.skills(CatalogInput.search(q),false,limit,offset),limit,offset);
    }
    @GetMapping("/v1/skills/{id}") Skill skill(@PathVariable UUID id) { return repository.skill(id,false); }
    @GetMapping("/v1/skills/admin")
    CatalogPage<Skill> adminSkills(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
        CatalogInput.page(limit,offset); return new CatalogPage<>(repository.skills(CatalogInput.search(q),true,limit,offset),limit,offset);
    }
    @GetMapping("/v1/skills/admin/{id}") Skill adminSkill(@PathVariable UUID id) { return repository.skill(id,true); }
    @PostMapping(value="/v1/skills/admin",consumes="application/json")
    ResponseEntity<Skill> create(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,Object> body) {
        Skill skill=service.create(CatalogInput.subject(jwt),body); return ResponseEntity.created(URI.create("/v1/skills/admin/"+skill.id())).body(skill);
    }
    @PatchMapping(value="/v1/skills/admin/{id}",consumes="application/json")
    Skill patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Map<String,Object> body) { return service.patch(CatalogInput.subject(jwt),id,body); }
    @GetMapping("/v1/hashtags")
    CatalogPage<Hashtag> hashtags(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        CatalogInput.page(limit,offset); return new CatalogPage<>(repository.hashtags(CatalogInput.search(q),limit,offset),limit,offset);
    }
    @GetMapping("/v1/hashtags/{id}") Hashtag hashtag(@PathVariable UUID id) { return repository.hashtag(id); }
    @PostMapping(value="/v1/hashtags",consumes="application/json")
    ResponseEntity<Hashtag> ensure(@RequestBody Map<String,Object> body) {
        EnsuredHashtag result=service.ensure(body);
        return result.created() ? ResponseEntity.created(URI.create("/v1/hashtags/"+result.hashtag().id())).body(result.hashtag()) : ResponseEntity.ok(result.hashtag());
    }
    @GetMapping("/v1/hashtags/following")
    CatalogPage<Hashtag> following(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        CatalogInput.page(limit,offset); return new CatalogPage<>(repository.following(CatalogInput.subject(jwt),limit,offset),limit,offset);
    }
    @PutMapping("/v1/hashtags/{id}/follow") ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        service.follow(CatalogInput.subject(jwt),id,true); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/v1/hashtags/{id}/follow") ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        service.follow(CatalogInput.subject(jwt),id,false); return ResponseEntity.noContent().build();
    }
}
