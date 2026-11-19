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

    @Test void accountEventsHideAndShowAnOwnerForEveryReader() {
        var handler = new UserRelationEventHandler(new UserRelationEventDecoder(), projection);
        handler.handle(account("alice", "DEACTIVATED"));
        assertThat(projection.deactivated()).containsExactly("alice");
        assertThat(projection.videoVersion()).isEqualTo("1");
        handler.handle(account("alice", "DEACTIVATED"));
        assertThat(projection.deactivated()).containsExactly("alice");
        handler.handle(account("alice", "ACTIVE"));
        assertThat(projection.deactivated()).isEmpty();
        assertThat(projection.videoVersion()).isEqualTo("3");
    }

    @Test void aClosingOrClosedAccountStaysHidden() {
        var handler = new UserRelationEventHandler(new UserRelationEventDecoder(), projection);
        handler.handle(account("alice", "CLOSING"));
        assertThat(projection.deactivated()).containsExactly("alice");
        handler.handle(account("alice", "CLOSED"));
        assertThat(projection.deactivated()).containsExactly("alice");
    }

    @Test void malformedAccountEventsAreRejected() {
        var decoder = new UserRelationEventDecoder();
        for (String data : new String[] {"{\"subject\":\"alice\",\"status\":\"BANNED\"}", "{\"subject\":\"\",\"status\":\"ACTIVE\"}",
                "{\"subject\":\"alice\"}", "{\"subject\":\"alice\",\"status\":\"ACTIVE\",\"extra\":1}"})
            assertThatThrownBy(() -> decoder.decode("youscout-user-relationships-v1", accountJson(data)))
                .isInstanceOf(UserRelationEventDecoder.InvalidRelationshipEvent.class);
    }

    static org.apache.kafka.clients.consumer.ConsumerRecord<String, String> account(String subject, String status) {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>("user.relationships.v1", 0, 0, "youscout-user-relationships-v1",
            accountJson("{\"subject\":\"" + subject + "\",\"status\":\"" + status + "\"}"));
    }
    static String accountJson(String data) {
        return "{\"eventId\":\"" + java.util.UUID.randomUUID() + "\",\"eventType\":\"UserAccountChanged\",\"eventVersion\":1,"
            + "\"occurredAt\":\"2026-11-18T10:00:00Z\",\"producer\":\"user-service\",\"data\":" + data + "}";
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
