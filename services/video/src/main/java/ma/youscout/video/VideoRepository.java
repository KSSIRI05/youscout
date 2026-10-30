package ma.youscout.video;

import java.util.UUID;
import java.util.Optional;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Paramètres liés et unicité en base pour isoler les propriétaires et les tentatives concurrentes. */
@Repository
class VideoRepository {
    private final JdbcTemplate jdbc;
    VideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean create(UUID id, String owner, UploadInput input, String bucket, String key, String idempotencyKey) {
        return jdbc.update("""
                INSERT INTO videos (id, owner_subject, title, s3_bucket, s3_key, content_type, size_bytes, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (owner_subject, idempotency_key) DO NOTHING
                """, id, owner, input.title(), bucket, key, input.contentType(), input.sizeBytes(), idempotencyKey) == 1;
    }

    VideoUpload find(String owner, String idempotencyKey) {
        return jdbc.queryForObject("SELECT * FROM videos WHERE owner_subject = ? AND idempotency_key = ?",
                this::map, owner, idempotencyKey);
    }

    // Le verrou sérialise les confirmations concurrentes ; la requête n'expose jamais un autre propriétaire.
    Optional<VideoUpload> lockOwned(UUID id, String owner) {
        return jdbc.query("SELECT * FROM videos WHERE id = ? AND owner_subject = ? FOR UPDATE",
                this::map, id, owner).stream().findFirst();
    }

    VideoUpload confirm(UUID id, String owner, String etag) {
        return jdbc.queryForObject("""
                UPDATE videos SET status = 'UPLOADED', source_etag = ?,
                    completed_at = statement_timestamp(), updated_at = statement_timestamp(),
                    processing_status = 'WAITING', processing_updated_at = statement_timestamp()
                WHERE id = ? AND owner_subject = ? AND status = 'PENDING_UPLOAD'
                RETURNING *
                """, this::map, etag, id, owner);
    }

    private VideoUpload map(ResultSet row, int index) throws SQLException {
        var completed = row.getTimestamp("completed_at");
        return new VideoUpload(row.getObject("id", UUID.class), row.getString("owner_subject"),
                row.getString("title"), row.getString("s3_bucket"), row.getString("s3_key"),
                row.getString("content_type"), row.getLong("size_bytes"), row.getString("status"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant(), row.getString("source_etag"));
    }
}
