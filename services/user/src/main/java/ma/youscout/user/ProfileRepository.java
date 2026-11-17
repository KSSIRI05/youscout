package ma.youscout.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

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

    UserProfile findIfPresent(String subject) {
        var rows = jdbc.query("SELECT * FROM user_profiles WHERE subject = ?", this::map, subject);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    List<String> following(String subject) {
        return jdbc.queryForList("SELECT followed_subject FROM user_follows WHERE follower_subject = ? ORDER BY created_at DESC", String.class, subject);
    }
    long followingCount(String subject) { return jdbc.queryForObject("SELECT count(*) FROM user_follows WHERE follower_subject = ?", Long.class, subject); }
    long followerCount(String subject) { return jdbc.queryForObject("SELECT count(*) FROM user_follows WHERE followed_subject = ?", Long.class, subject); }
    boolean isFollowing(String from, String to) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_follows WHERE follower_subject = ? AND followed_subject = ?)", Boolean.class, from, to)); }
    boolean isBlockedEitherWay(String first, String second) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM user_blocks
                          WHERE (blocker_subject = ? AND blocked_subject = ?)
                             OR (blocker_subject = ? AND blocked_subject = ?))
            """, Boolean.class, first, second, second, first));
    }
    void lockRelationship(String first, String second) {
        String low = first.compareTo(second) < 0 ? first : second;
        String high = first.compareTo(second) < 0 ? second : first;
        String canonical = low.length() + ":" + low + high.length() + ":" + high;
        String pair;
        try {
            pair = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
        jdbc.update("INSERT INTO user_relation_locks(pair_key) VALUES (?) ON CONFLICT DO NOTHING", pair);
        jdbc.queryForObject("SELECT pair_key FROM user_relation_locks WHERE pair_key = ? FOR UPDATE",
                String.class, pair);
    }
    void follow(String from, String to) { jdbc.update("INSERT INTO user_follows(follower_subject, followed_subject) VALUES (?, ?) ON CONFLICT DO NOTHING", from, to); }
    void unfollow(String from, String to) { jdbc.update("DELETE FROM user_follows WHERE follower_subject = ? AND followed_subject = ?", from, to); }
    List<String> blocked(String subject) {
        return jdbc.queryForList("SELECT blocked_subject FROM user_blocks WHERE blocker_subject = ? ORDER BY created_at DESC", String.class, subject);
    }
    List<String> blockedBy(String subject) {
        return jdbc.queryForList("SELECT blocker_subject FROM user_blocks WHERE blocked_subject = ? ORDER BY created_at DESC", String.class, subject);
    }
    void block(String from, String to) {
        jdbc.update("INSERT INTO user_blocks(blocker_subject, blocked_subject) VALUES (?, ?) ON CONFLICT DO NOTHING", from, to);
        unfollow(from, to);
        unfollow(to, from);
    }
    void unblock(String from, String to) {
        jdbc.update("DELETE FROM user_blocks WHERE blocker_subject = ? AND blocked_subject = ?", from, to);
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
