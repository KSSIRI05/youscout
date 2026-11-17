package ma.youscout.video;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class DeletionController {
    private final DeletionService deletions;
    DeletionController(DeletionService deletions) { this.deletions = deletions; }

    /** Only the owner can delete; repeating the call returns 204 again. Admins cannot delete another account's video. */
    @DeleteMapping("/v1/videos/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        deletions.delete(id, jwt.getSubject());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
