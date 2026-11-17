package ma.youscout.feed;

import java.util.List;
import java.util.Set;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis projection of follows and blocks, updated atomically from the User event stream. */
@Component
class FeedRelationsProjection {
    private static final String PREFIX = "youscout:feed:relations:";
    private static final String READY_KEY = PREFIX + "snapshot-ready";
    private static final String HEARTBEAT_KEY = PREFIX + "consumer-heartbeat";
    private static final DefaultRedisScript<Long> APPLY = new DefaultRedisScript<>("""
        local action = ARGV[1]
        local actor = ARGV[2]
        local target = ARGV[3]
        if action == 'FOLLOW' then
          if redis.call('SISMEMBER', KEYS[2], target) == 0
             and redis.call('SISMEMBER', KEYS[3], target) == 0 then
            redis.call('SADD', KEYS[1], target)
          end
        elseif action == 'UNFOLLOW' then
          redis.call('SREM', KEYS[1], target)
        elseif action == 'BLOCK' then
          redis.call('SADD', KEYS[2], target)
          redis.call('SADD', KEYS[4], actor)
          redis.call('SREM', KEYS[1], target)
          redis.call('SREM', KEYS[5], actor)
        elseif action == 'UNBLOCK' then
          redis.call('SREM', KEYS[2], target)
          redis.call('SREM', KEYS[4], actor)
        else
          return redis.error_reply('invalid relationship action')
        end
        redis.call('INCR', KEYS[6])
        redis.call('INCR', KEYS[7])
        return 1
        """, Long.class);

    private final StringRedisTemplate redis;

    FeedRelationsProjection(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void apply(UserRelationEvent.Data event) {
        String actor = event.actorSubject();
        String target = event.targetSubject();
        redis.execute(APPLY, List.of(followingKey(actor), blockingKey(actor), blockedByKey(actor),
                blockedByKey(target), followingKey(target), versionKey(actor), versionKey(target)),
                event.action(), actor, target);
    }

    void markSnapshotComplete() {
        redis.opsForValue().set(READY_KEY, "true");
    }

    boolean ready() {
        return Boolean.TRUE.equals(redis.hasKey(READY_KEY));
    }

    void heartbeat() {
        redis.opsForValue().set(HEARTBEAT_KEY, "active", Duration.ofSeconds(15));
    }

    boolean available() {
        return ready() && Boolean.TRUE.equals(redis.hasKey(HEARTBEAT_KEY));
    }

    List<String> following(String subject) { return members(followingKey(subject)); }
    List<String> blocked(String subject) { return members(blockingKey(subject)); }
    List<String> blockedBy(String subject) { return members(blockedByKey(subject)); }

    String version(String subject) {
        return value(versionKey(subject));
    }

    String videoVersion() { return value(PREFIX + "video-version"); }

    void videoPublished() {
        redis.opsForValue().increment(PREFIX + "video-version");
    }

    private List<String> members(String key) {
        Set<String> values = redis.opsForSet().members(key);
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private String value(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? "0" : value;
    }

    private static String followingKey(String subject) { return PREFIX + "following:" + subject; }
    private static String blockingKey(String subject) { return PREFIX + "blocking:" + subject; }
    private static String blockedByKey(String subject) { return PREFIX + "blocked-by:" + subject; }
    private static String versionKey(String subject) { return PREFIX + "version:" + subject; }
}
