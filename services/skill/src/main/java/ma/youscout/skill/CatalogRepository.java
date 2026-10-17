package ma.youscout.skill;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL du domaine Skill/Hashtag ; chaque paramètre client est lié par JdbcTemplate. */
@Repository
class CatalogRepository {
    private final JdbcTemplate jdbc;
    CatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // POSITION ne traite pas % et _ comme des jokers ; le tri stabilise la pagination.
    List<Skill> skills(String query, boolean includeInactive, int limit, int offset) {
        return jdbc.query("SELECT * FROM skills WHERE (? OR active) AND position(? in name_key) > 0 ORDER BY name_key, id LIMIT ? OFFSET ?",
            this::mapSkill, includeInactive, query, limit, offset);
    }
    Skill skill(UUID id, boolean includeInactive) {
        return jdbc.query("SELECT * FROM skills WHERE id = ? AND (? OR active)", this::mapSkill, id, includeInactive)
            .stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Compétence introuvable."));
    }
    Skill createSkill(String name, String description) {
        return jdbc.queryForObject("INSERT INTO skills(id,name,name_key,description) VALUES (?,?,?,?) RETURNING *",
            this::mapSkill, UUID.randomUUID(), name, CatalogInput.key(name), description);
    }
    // COALESCE conserve les champs absents ; RETURNING renvoie la ligne effectivement mise à jour.
    Skill patchSkill(UUID id, String name, String description, Boolean active) {
        var rows = jdbc.query("""
            UPDATE skills SET name=COALESCE(?,name), name_key=COALESCE(?,name_key),
            description=COALESCE(?,description), active=COALESCE(?,active), updated_at=clock_timestamp()
            WHERE id=? RETURNING *
            """, this::mapSkill, name, name == null ? null : CatalogInput.key(name), description, active, id);
        return rows.stream().findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Compétence introuvable."));
    }
    // Cette insertion participe à la transaction du service ; ce journal n’est pas une outbox Kafka.
    void audit(UUID skillId, String actor, String action) {
        jdbc.update("INSERT INTO skill_audit(id,skill_id,actor_subject,action) VALUES (?,?,?,?)", UUID.randomUUID(),skillId,actor,action);
    }
    // La contrainte unique arbitre les créations concurrentes ; on relit ensuite l’identifiant gagnant.
    EnsuredHashtag ensureHashtag(String name) {
        int changed = jdbc.update("INSERT INTO hashtags(id,name) VALUES (?,?) ON CONFLICT(name) DO NOTHING", UUID.randomUUID(), name);
        return new EnsuredHashtag(jdbc.queryForObject("SELECT * FROM hashtags WHERE name=?", this::mapHashtag, name), changed == 1);
    }
    Hashtag hashtag(UUID id) {
        return jdbc.query("SELECT * FROM hashtags WHERE id=?", this::mapHashtag, id).stream().findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Hashtag introuvable."));
    }
    List<Hashtag> hashtags(String query, int limit, int offset) {
        return jdbc.query("SELECT * FROM hashtags WHERE position(? in name)>0 ORDER BY name,id LIMIT ? OFFSET ?", this::mapHashtag, query, limit, offset);
    }
    // Le filtre subject empêche de consulter les abonnements d’un autre compte.
    List<Hashtag> following(String subject, int limit, int offset) {
        return jdbc.query("SELECT h.* FROM hashtags h JOIN hashtag_follows f ON f.hashtag_id=h.id WHERE f.subject=? ORDER BY h.name,h.id LIMIT ? OFFSET ?",
            this::mapHashtag, subject, limit, offset);
    }
    // La clé primaire (subject, hashtag_id) rend les abonnements répétés sans effet supplémentaire.
    void follow(String subject, UUID id) {
        jdbc.update("INSERT INTO hashtag_follows(subject,hashtag_id) VALUES (?,?) ON CONFLICT DO NOTHING", subject, id);
    }
    void unfollow(String subject, UUID id) { jdbc.update("DELETE FROM hashtag_follows WHERE subject=? AND hashtag_id=?", subject, id); }
    // Transforme les colonnes PostgreSQL en objets de réponse ; les dates deviennent des Instant.
    private Skill mapSkill(ResultSet row, int index) throws SQLException {
        return new Skill(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"), row.getBoolean("active"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    private Hashtag mapHashtag(ResultSet row, int index) throws SQLException {
        return new Hashtag(row.getObject("id", UUID.class), row.getString("name"), row.getTimestamp("created_at").toInstant());
    }
}
