package ma.youscout.feed;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exécute le script Lua de projection sur un vrai Redis. */
@Testcontainers
class FeedRelationsProjectionTest {
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connections;
    StringRedisTemplate redis;
    FeedRelationsProjection projection;

    @BeforeAll static void connect() {
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
    }
    @AfterAll static void disconnect() { connections.destroy(); }
    @BeforeEach void setup() {
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        projection = new FeedRelationsProjection(redis);
    }

    @Test void followAndUnfollowUpdateTheActorAndBumpBothVersions() {
        apply("FOLLOW", "alice", "bob");
        apply("FOLLOW", "alice", "bob");
        assertThat(projection.following("alice")).containsExactly("bob");
        assertThat(projection.version("alice")).isEqualTo("2");
        assertThat(projection.version("bob")).isEqualTo("2");
        apply("UNFOLLOW", "alice", "bob");
        assertThat(projection.following("alice")).isEmpty();
    }

    @Test void blockRemovesFollowsBothWaysAndIgnoresLaterFollowsUntilUnblocked() {
        apply("FOLLOW", "alice", "bob");
        apply("FOLLOW", "bob", "alice");
        apply("BLOCK", "bob", "alice");
        assertThat(projection.following("alice")).isEmpty();
        assertThat(projection.following("bob")).isEmpty();
        assertThat(projection.blocked("bob")).containsExactly("alice");
        assertThat(projection.blockedBy("alice")).containsExactly("bob");

        apply("FOLLOW", "bob", "alice");
        assertThat(projection.following("bob")).isEmpty();
        apply("UNBLOCK", "bob", "alice");
        assertThat(projection.blocked("bob")).isEmpty();
        assertThat(projection.blockedBy("alice")).isEmpty();
        apply("FOLLOW", "bob", "alice");
        assertThat(projection.following("bob")).containsExactly("alice");
    }

    @Test void unknownActionsAreRejectedWithoutChangingVersions() {
        assertThatThrownBy(() -> apply("MUTE", "alice", "bob")).isInstanceOf(RuntimeException.class);
        assertThat(projection.version("alice")).isEqualTo("0");
    }

    @Test void feedIsAvailableOnlyAfterSnapshotAndWhileTheConsumerIsAlive() {
        assertThat(projection.available()).isFalse();
        projection.heartbeat();
        assertThat(projection.available()).isFalse();
        projection.markSnapshotComplete();
        assertThat(projection.available()).isTrue();
        redis.delete("youscout:feed:relations:consumer-heartbeat");
        assertThat(projection.available()).isFalse();
    }

    @Test void publishingAVideoInvalidatesEveryCachedPageVersion() {
        assertThat(projection.videoVersion()).isEqualTo("0");
        projection.videosChanged();
        assertThat(projection.videoVersion()).isEqualTo("1");
    }

    void apply(String action, String actor, String target) {
        projection.apply(new UserRelationEvent.Data(action, actor, target));
    }
}
