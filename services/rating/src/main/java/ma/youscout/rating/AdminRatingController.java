package ma.youscout.rating;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A06 : totaux des notes ; route réservée au rôle admin (Gateway et SecurityConfig). */
@RestController
class AdminRatingController {
    record RatingTotals(long ratings, long raters, long ratedVideos) {}

    private final JdbcTemplate jdbc;

    AdminRatingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/ratings/admin/stats")
    ResponseEntity<RatingTotals> stats() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jdbc.queryForObject(
            "SELECT count(*), count(DISTINCT rater_subject), count(DISTINCT video_id) FROM skill_ratings",
            (r, n) -> new RatingTotals(r.getLong(1), r.getLong(2), r.getLong(3))));
    }
}
