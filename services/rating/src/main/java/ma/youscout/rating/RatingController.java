package ma.youscout.rating;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * U15 : noter de 1 à 5 chaque compétence d'une vidéo publiée, puis modifier ou retirer sa note.
 * Une note par évaluateur, vidéo et compétence ; la modification est un seul upsert atomique.
 */
@RestController
@RequestMapping("/v1/ratings/videos/{videoId}")
class RatingController {
    record SkillRating(UUID skillId, long count, BigDecimal average, Integer myScore) {}
    record VideoRatings(UUID videoId, List<SkillRating> skills) {}

    private final JdbcTemplate jdbc;
    private final RatingReferences references;

    RatingController(JdbcTemplate jdbc, RatingReferences references) {
        this.jdbc = jdbc;
        this.references = references;
    }

    /** Moyenne et nombre de notes par compétence notée, avec la note de l'appelant. */
    @GetMapping
    ResponseEntity<VideoRatings> ratings(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        String subject = subject(jwt);
        List<SkillRating> skills = jdbc.query("SELECT skill_id, count(*), round(avg(score), 2),"
            + " max(score) FILTER (WHERE rater_subject = ?) FROM skill_ratings WHERE video_id = ? GROUP BY skill_id ORDER BY skill_id",
            (r, n) -> new SkillRating(r.getObject(1, UUID.class), r.getLong(2), r.getBigDecimal(3), (Integer) r.getObject(4, Integer.class)),
            subject, videoId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VideoRatings(videoId, skills));
    }

    @PutMapping("/skills/{skillId}")
    ResponseEntity<VideoRatings> rate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @PathVariable UUID skillId,
                                      @RequestBody Map<String, Object> body) {
        String rater = subject(jwt);
        int score = score(body);
        var video = references.video(videoId, jwt.getTokenValue());
        if (!video.skillIds().contains(skillId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cette compétence n'est pas associée à la vidéo.");
        if (video.ownerSubject().equals(rater))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "On ne peut pas noter sa propre vidéo.");
        if (references.blockedEitherWay(jwt.getTokenValue()).contains(video.ownerSubject()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un blocage empêche cette note.");
        jdbc.update("INSERT INTO skill_ratings(rater_subject, video_id, skill_id, score) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (rater_subject, video_id, skill_id) DO UPDATE SET score = EXCLUDED.score, updated_at = clock_timestamp()"
            + " WHERE skill_ratings.score <> EXCLUDED.score", rater, videoId, skillId, score);
        return ratings(jwt, videoId);
    }

    // Retirer sa note reste toujours possible, même après un blocage ou un retrait de la vidéo.
    @DeleteMapping("/skills/{skillId}")
    ResponseEntity<VideoRatings> unrate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId, @PathVariable UUID skillId) {
        jdbc.update("DELETE FROM skill_ratings WHERE rater_subject = ? AND video_id = ? AND skill_id = ?", subject(jwt), videoId, skillId);
        return ratings(jwt, videoId);
    }

    private static int score(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(java.util.Set.of("score")) || !(body.get("score") instanceof Integer score)
                || score < 1 || score > 5)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La note doit être un entier de 1 à 5.");
        return score;
    }

    private static String subject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité absente du jeton.");
        return subject;
    }
}
