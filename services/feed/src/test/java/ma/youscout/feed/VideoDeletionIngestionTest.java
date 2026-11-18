package ma.youscout.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Applique les migrations Feed sur un vrai PostgreSQL et rejoue les événements comme le consumer Kafka. */
@Testcontainers
class VideoDeletionIngestionTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final String PUBLISHED = "video.published.v2", DELETED = "video.deleted.v1", MODERATED = "video.moderation.v1";
    static JdbcTemplate jdbc;
    final FeedRelationsProjection projection = mock(FeedRelationsProjection.class);
    PublishedVideoHandler handler;
    FeedStore store;
    long offset;

    @BeforeAll static void migrate() {
        var source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl()); source.setUser(postgres.getUsername()); source.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }
    @BeforeEach void setup() {
        jdbc.update("TRUNCATE feed_entries, feed_event_inbox, feed_deleted_videos, feed_moderation, feed_rejected_messages");
        store = new FeedStore(jdbc);
        handler = new PublishedVideoHandler(new PublishedVideoDecoder(), new DeletedVideoDecoder(), new ModeratedVideoDecoder(),
            store, projection, new FeedIngestionProperties(true, "unused", PUBLISHED, DELETED, MODERATED, "test"));
    }

    @Test void deletionRemovesTheEntryAndInvalidatesCachedPagesOnce() {
        UUID video = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        assertThat(store.page(20, 0)).extracting(FeedStore.Entry::videoId).containsExactly(video);
        String deletion = deleted(video, "alice");
        handle(DELETED, video, deletion);
        handle(DELETED, video, deletion);

        assertThat(store.page(20, 0)).isEmpty();
        assertThat(count("feed_deleted_videos")).isEqualTo(1);
        assertThat(count("feed_rejected_messages")).isZero();
        verify(projection, times(2)).videosChanged();
    }

    @Test void deletionReadBeforePublicationPreventsTheEntryFromAppearing() {
        UUID video = UUID.randomUUID();
        handle(DELETED, video, deleted(video, "alice"));
        handle(PUBLISHED, video, published(video, "alice"));

        assertThat(store.page(20, 0)).isEmpty();
        assertThat(count("feed_event_inbox")).isEqualTo(1);
        verify(projection, never()).videosChanged();
    }

    @Test void otherVideosStayVisible() {
        UUID kept = UUID.randomUUID(), removed = UUID.randomUUID();
        handle(PUBLISHED, kept, published(kept, "alice"));
        handle(PUBLISHED, removed, published(removed, "bob"));
        handle(DELETED, removed, deleted(removed, "bob"));
        assertThat(store.page(20, 0)).extracting(FeedStore.Entry::videoId).containsExactly(kept);
    }

    @Test void deletionClaimingAnotherOwnerIsQuarantinedAndChangesNothing() {
        UUID video = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        reset(projection);
        handle(DELETED, video, deleted(video, "mallory"));

        assertThat(store.page(20, 0)).hasSize(1);
        assertThat(count("feed_deleted_videos")).isZero();
        assertThat(jdbc.queryForObject("SELECT reason FROM feed_rejected_messages", String.class)).isEqualTo("VIDEO_EVENT_CONFLICT");
        verify(projection, never()).videosChanged();
    }

    @Test void malformedDeletionsAreQuarantinedWithoutTouchingTheFeed() {
        UUID video = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        handle(DELETED, video, "{\"eventType\":\"VideoDeleted\"}");
        handle(DELETED, UUID.randomUUID(), deleted(video, "alice"));

        assertThat(store.page(20, 0)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT reason FROM feed_rejected_messages ORDER BY record_offset", String.class))
            .containsExactly("INVALID_ENVELOPE", "INVALID_MESSAGE_KEY");
    }

    @Test void decoderEnforcesTheV1Contract() {
        UUID video = UUID.randomUUID();
        var decoder = new DeletedVideoDecoder();
        DeletedVideo decoded = decoder.decode(video.toString(), deleted(video, "alice"));
        assertThat(decoded.videoId()).isEqualTo(video);
        assertThat(decoded.ownerSubject()).isEqualTo("alice");
        for (String invalid : new String[] {
            deleted(video, "alice").replace("\"eventVersion\":1", "\"eventVersion\":2"),
            deleted(video, "alice").replace("\"wasPublished\":true", "\"wasPublished\":\"yes\""),
            deleted(video, "alice").replace("\"occurredAt\":\"2026-01-02T12:00:00Z\"", "\"occurredAt\":\"2026-01-03T12:00:00Z\""),
            deleted(video, "alice").replace("\"wasPublished\":true", "\"wasPublished\":true,\"s3Key\":\"x\""),
            published(video, "alice") })
            assertThatThrownBy(() -> decoder.decode(video.toString(), invalid)).isInstanceOf(PublishedVideoDecoder.InvalidEvent.class);
    }

    @Test void takedownHidesTheEntryAndRestoreShowsItAgain() {
        UUID video = UUID.randomUUID(), other = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        handle(PUBLISHED, other, published(other, "bob"));
        reset(projection);
        handle(MODERATED, video, moderated(video, "alice", "REMOVED", 1));
        assertThat(store.page(20, 0)).extracting(FeedStore.Entry::videoId).containsExactly(other);
        assertThat(count("feed_entries")).isEqualTo(2);
        handle(MODERATED, video, moderated(video, "alice", "ACTIVE", 2));
        assertThat(store.page(20, 0)).extracting(FeedStore.Entry::videoId).containsExactlyInAnyOrder(video, other);
        verify(projection, times(2)).videosChanged();
    }

    @Test void olderOrReplayedModerationNeverOverridesANewerDecision() {
        UUID video = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        handle(MODERATED, video, moderated(video, "alice", "REMOVED", 3));
        handle(MODERATED, video, moderated(video, "alice", "ACTIVE", 2));
        handle(MODERATED, video, moderated(video, "alice", "REMOVED", 3));
        assertThat(store.page(20, 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT version FROM feed_moderation", Long.class)).isEqualTo(3L);
    }

    @Test void takedownReadBeforePublicationKeepsTheVideoHidden() {
        UUID video = UUID.randomUUID();
        handle(MODERATED, video, moderated(video, "alice", "REMOVED", 1));
        handle(PUBLISHED, video, published(video, "alice"));
        assertThat(store.page(20, 0)).isEmpty();
        verify(projection, never()).videosChanged();
    }

    @Test void moderationEventsWithAnotherOwnerOrBadFieldsAreQuarantined() {
        UUID video = UUID.randomUUID();
        handle(PUBLISHED, video, published(video, "alice"));
        handle(MODERATED, video, moderated(video, "mallory", "REMOVED", 1));
        handle(MODERATED, video, moderated(video, "alice", "BANNED", 1));
        handle(MODERATED, video, moderated(video, "alice", "REMOVED", 0));
        assertThat(store.page(20, 0)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT reason FROM feed_rejected_messages ORDER BY record_offset", String.class))
            .containsExactly("VIDEO_EVENT_CONFLICT", "INVALID_ENVELOPE", "INVALID_ENVELOPE");
    }

    static String moderated(UUID video, String owner, String action, long version) {
        return """
            {"eventId":"%s","eventType":"VideoModerated","eventVersion":1,"occurredAt":"2026-01-03T12:00:00Z","producer":"video-service",
             "data":{"videoId":"%s","ownerSubject":"%s","action":"%s","version":%d,"moderatedAt":"2026-01-03T12:00:00Z"}}
            """.formatted(UUID.randomUUID(), video, owner, action, version);
    }

    void handle(String topic, UUID key, String value) { handler.handle(new ConsumerRecord<>(topic, 0, offset++, key.toString(), value)); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    static String published(UUID video, String owner) {
        return """
            {"eventId":"%s","eventType":"VideoPublished","eventVersion":2,"occurredAt":"2026-01-01T12:00:00Z","producer":"video-service",
             "data":{"videoId":"%s","ownerSubject":"%s","title":"Clip","publishedAt":"2026-01-01T12:00:00Z","skills":[],"hashtags":[]}}
            """.formatted(UUID.randomUUID(), video, owner);
    }
    static String deleted(UUID video, String owner) {
        return """
            {"eventId":"%s","eventType":"VideoDeleted","eventVersion":1,"occurredAt":"2026-01-02T12:00:00Z","producer":"video-service",
             "data":{"videoId":"%s","ownerSubject":"%s","deletedAt":"2026-01-02T12:00:00Z","wasPublished":true}}
            """.formatted(UUID.randomUUID(), video, owner);
    }
}
