package ma.youscout.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Accède uniquement à la base User avec des paramètres SQL liés, sans concaténation des entrées. */
@Repository
class ProfileRepository {
    private final JdbcTemplate jdbc;

    ProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Deux premières requêtes concurrentes ne doivent créer qu’un seul profil pour le même sujet.
    void createIfAbsent(String subject, String initialName) {
        jdbc.update("""
            INSERT INTO user_profiles (subject, display_name) VALUES (?, ?)
            ON CONFLICT (subject) DO NOTHING
            """, subject, initialName);
    }

    UserProfile find(String subject) {
        return jdbc.queryForObject("SELECT * FROM user_profiles WHERE subject = ?", this::map, subject);
    }

    UserProfile patch(String subject, ProfilePatch patch) {
        // Un seul UPDATE préserve les champs absents, même si des PATCH concurrents ciblent des champs différents.
        return jdbc.queryForObject("""
            UPDATE user_profiles
            SET display_name = COALESCE(?, display_name), biography = COALESCE(?, biography),
                updated_at = clock_timestamp()
            WHERE subject = ?
            RETURNING *
            """, this::map, patch.displayName(), patch.biography(), subject);
    }

    private UserProfile map(ResultSet row, int index) throws SQLException {
        return new UserProfile(row.getString("subject"), row.getString("display_name"), row.getString("biography"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
