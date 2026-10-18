package ma.youscout.video;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Le seul propriétaire accepté est le sub du JWT validé, jamais un champ JSON ou un en-tête. */
@RestController
class UploadController {
    private final UploadService uploads;
    UploadController(UploadService uploads) { this.uploads = uploads; }

    @PostMapping(value = "/v1/videos/uploads", consumes = "application/json")
    ResponseEntity<UploadService.Ticket> prepare(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> fields) {
        var result = uploads.prepare(jwt.getSubject(), idempotencyKey, fields);
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore()).body(result.ticket());
    }
}
