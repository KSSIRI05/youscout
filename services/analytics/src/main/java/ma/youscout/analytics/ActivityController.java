package ma.youscout.analytics;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A06 : activité quotidienne des {@code days} derniers jours UTC, aujourd'hui compris, jours sans activité à zéro.
 * {@code coverageStart} est le premier jour compté : Analytics ne voit que les événements encore retenus par Kafka
 * à son premier démarrage (7 jours), l'historique plus ancien n'est pas reconstruit.
 */
@RestController
class ActivityController {
    record Day(LocalDate day, long newAccounts, long videosPublished, long likes, long comments, long messages) {}
    record Activity(LocalDate from, LocalDate to, LocalDate coverageStart, List<Day> days) {}

    private final JdbcTemplate jdbc;

    ActivityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/analytics/activity")
    ResponseEntity<Activity> activity(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 90) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La période doit compter de 1 à 90 jours.");
        LocalDate to = LocalDate.now(ZoneOffset.UTC), from = to.minusDays(days - 1L);
        Map<LocalDate, Map<String, Long>> counts = new HashMap<>();
        jdbc.query("SELECT day, metric, count FROM daily_activity WHERE day BETWEEN ? AND ?",
            (java.sql.ResultSet r) -> {
                counts.computeIfAbsent(r.getDate(1).toLocalDate(), d -> new HashMap<>()).put(r.getString(2), r.getLong(3));
            }, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        var series = new ArrayList<Day>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Map<String, Long> c = counts.getOrDefault(day, Map.of());
            series.add(new Day(day, c.getOrDefault("NEW_ACCOUNTS", 0L), c.getOrDefault("VIDEOS_PUBLISHED", 0L), c.getOrDefault("LIKES", 0L),
                c.getOrDefault("COMMENTS", 0L), c.getOrDefault("MESSAGES", 0L)));
        }
        java.sql.Date first = jdbc.queryForObject("SELECT min(day) FROM daily_activity", java.sql.Date.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Activity(from, to, first == null ? null : first.toLocalDate(), series));
    }
}
