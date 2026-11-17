package ma.youscout.feed;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** L'inbox et la projection empêchent les rediffusions de créer plusieurs entrées publiques. */
@Repository
class FeedStore {
    private record Existing(String ownerSubject, String title, Timestamp publishedAt, String skills, String hashtags) {}
    record Entry(UUID videoId, String title, java.time.Instant publishedAt, String ownerSubject, JsonNode skills, JsonNode hashtags) {
        Entry(UUID videoId, String title, java.time.Instant publishedAt) {
            this(videoId, title, publishedAt, "", JsonMapper.builder().build().createArrayNode(), JsonMapper.builder().build().createArrayNode());
        }
    }
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    FeedStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Entry> page(int limit, int offset) { return page(limit, offset, null, List.of()); }

    List<Entry> page(int limit, int offset, List<String> owners, List<String> excludedOwners) {
        if (owners != null && owners.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT video_id, title, published_at, owner_subject, skills::text, hashtags::text FROM feed_entries");
        var args = new java.util.ArrayList<Object>();
        boolean hasPredicate = false;
        if (owners != null) {
            appendSubjectsPredicate(sql, args, "owner_subject IN", owners, hasPredicate);
            hasPredicate = true;
        }
        if (!excludedOwners.isEmpty()) appendSubjectsPredicate(sql, args, "owner_subject NOT IN", excludedOwners, hasPredicate);
        sql.append(" ORDER BY published_at DESC, video_id DESC LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), this::mapEntry, args.toArray());
    }

    private static void appendSubjectsPredicate(StringBuilder sql, List<Object> args, String operator,
                                                List<String> subjects, boolean hasPredicate) {
        sql.append(hasPredicate ? " AND " : " WHERE ").append(operator).append(" (")
            .append(String.join(",", java.util.Collections.nCopies(subjects.size(), "?"))).append(')');
        args.addAll(subjects);
    }

    private Entry mapEntry(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new Entry(row.getObject(1, UUID.class), row.getString(2), row.getTimestamp(3).toInstant(), row.getString(4),
            mapper.readTree(row.getString(5)), mapper.readTree(row.getString(6)));
    }

    @Transactional(timeout = 10)
    public void accept(PublishedVideo event, String topic, int partition, long offset) {
        int inserted = jdbc.update("""
            INSERT INTO feed_event_inbox(event_id, video_id, fingerprint)
            VALUES (?, ?, ?) ON CONFLICT DO NOTHING
            """, event.eventId(), event.videoId(), event.fingerprint());
        if (inserted == 0) {
            List<String> prior = jdbc.queryForList("SELECT fingerprint FROM feed_event_inbox WHERE event_id = ?", String.class, event.eventId());
            if (!prior.isEmpty() && prior.getFirst().equals(event.fingerprint())) return;
            reject(topic, partition, offset, "EVENT_ID_CONFLICT");
            return;
        }

        String skills = mapper.writeValueAsString(event.skills());
        String hashtags = mapper.writeValueAsString(event.hashtags());
        int entry = jdbc.update("""
            INSERT INTO feed_entries(video_id, source_event_id, owner_subject, title, published_at, skills, hashtags)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT (video_id) DO NOTHING
            """, event.videoId(), event.eventId(), event.ownerSubject(), event.title(), Timestamp.from(event.publishedAt()), skills, hashtags);
        if (entry == 1) return;

        List<Existing> prior = jdbc.query("SELECT owner_subject, title, published_at, skills::text, hashtags::text FROM feed_entries WHERE video_id = ?",
            (row, number) -> new Existing(row.getString(1), row.getString(2), row.getTimestamp(3), row.getString(4), row.getString(5)), event.videoId());
        if (!prior.isEmpty()) {
            Existing current = prior.getFirst();
            if (current.ownerSubject().equals(event.ownerSubject()) && current.title().equals(event.title())
                    && current.publishedAt().toInstant().equals(event.publishedAt())
                    && mapper.readTree(current.skills()).equals(mapper.readTree(skills))
                    && mapper.readTree(current.hashtags()).equals(mapper.readTree(hashtags))) return;
        }
        reject(topic, partition, offset, "VIDEO_EVENT_CONFLICT");
    }

    @Transactional(timeout = 10)
    public void reject(String topic, int partition, long offset, String reason) {
        jdbc.update("""
            INSERT INTO feed_rejected_messages(topic, partition_id, record_offset, reason)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
            """, topic, partition, offset, reason);
    }
}
