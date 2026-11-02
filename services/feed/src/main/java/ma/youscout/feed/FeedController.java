package ma.youscout.feed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/feed")
class FeedController {
    record Entry(UUID videoId, String title, java.time.Instant publishedAt, String ownerSubject,
                 JsonNode skills, JsonNode hashtags) {}
    record Page(List<Entry> items, boolean hasMore) {}
    private record CachedEntry(UUID videoId, String title, String publishedAt, String ownerSubject,
                               JsonNode skills, JsonNode hashtags) {}
    private record CachedPage(List<CachedEntry> items, boolean hasMore) {}

    private static final int PAGE_SIZE = 20;
    private static final String CACHE_PREFIX = "youscout:feed:page:";
    private final FeedStore feed;
    private final FeedRelationsProjection relations;
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration cacheTtl;

    @Autowired
    FeedController(FeedStore feed, FeedRelationsProjection relations, StringRedisTemplate redis,
                   @Value("${feed.cache.ttl:60s}") Duration cacheTtl) {
        this.feed = feed;
        this.relations = relations;
        this.redis = redis;
        this.cacheTtl = cacheTtl;
    }

    /** Test/compatibility constructor for the non-personalized database pagination path. */
    FeedController(FeedStore feed) {
        this.feed = feed;
        this.relations = null;
        this.redis = null;
        this.cacheTtl = Duration.ZERO;
    }

    @GetMapping
    ResponseEntity<Page> list(@RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "false") boolean following,
                              @AuthenticationPrincipal Jwt jwt) {
        validateOffset(offset);
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité de session invalide.");
        }
        try {
            if (!relations.available()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "La projection des abonnements du fil est en cours d'initialisation.");
            }
            String relationVersion = relations.version(subject);
            String videoVersion = relations.videoVersion();
            String cacheKey = cacheKey(subject, following, offset, relationVersion, videoVersion);
            Page cached = readCache(cacheKey);
            if (cached != null) return response(cached);

            List<String> owners = following ? relations.following(subject) : null;
            var excluded = new LinkedHashSet<String>();
            excluded.addAll(relations.blocked(subject));
            excluded.addAll(relations.blockedBy(subject));
            var rows = feed.page(PAGE_SIZE + 1, offset, owners, new ArrayList<>(excluded));
            var items = rows.subList(0, Math.min(PAGE_SIZE, rows.size())).stream()
                    .map(row -> new Entry(row.videoId(), row.title(), row.publishedAt(), row.ownerSubject(),
                            row.skills(), row.hashtags())).toList();
            var page = new Page(items, rows.size() > PAGE_SIZE && offset + PAGE_SIZE <= 10000);
            writeCache(cacheKey, page);
            return response(page);
        } catch (ResponseStatusException unavailable) {
            throw unavailable;
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La projection du fil est momentanément indisponible.");
        }
    }

    /** Preserves the unit-level database pagination helper. */
    ResponseEntity<Page> list(int offset) {
        validateOffset(offset);
        var rows = feed.page(PAGE_SIZE + 1, offset);
        var items = rows.subList(0, Math.min(PAGE_SIZE, rows.size())).stream()
                .map(row -> new Entry(row.videoId(), row.title(), row.publishedAt(), row.ownerSubject(),
                        row.skills(), row.hashtags())).toList();
        return response(new Page(items, rows.size() > PAGE_SIZE && offset + PAGE_SIZE <= 10000));
    }

    private Page readCache(String key) {
        String cached = redis.opsForValue().get(key);
        if (cached == null) return null;
        try {
            CachedPage page = json.readValue(cached, CachedPage.class);
            var entries = page.items().stream().map(item -> new Entry(item.videoId(), item.title(),
                    java.time.Instant.parse(item.publishedAt()), item.ownerSubject(), item.skills(), item.hashtags())).toList();
            return new Page(entries, page.hasMore());
        } catch (RuntimeException malformed) {
            redis.delete(key);
            return null;
        }
    }

    private void writeCache(String key, Page page) {
        var entries = page.items().stream().map(item -> new CachedEntry(item.videoId(), item.title(),
                item.publishedAt().toString(), item.ownerSubject(), item.skills(), item.hashtags())).toList();
        redis.opsForValue().set(key, json.writeValueAsString(new CachedPage(entries, page.hasMore())), cacheTtl);
    }

    private static String cacheKey(String subject, boolean following, int offset,
                                   String relationVersion, String videoVersion) {
        try {
            String userHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(StandardCharsets.UTF_8)));
            return CACHE_PREFIX + userHash + ':' + following + ':' + offset + ':' + relationVersion + ':' + videoVersion;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static ResponseEntity<Page> response(Page page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    private static void validateOffset(int offset) {
        if (offset < 0 || offset > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagination invalide.");
        }
    }
}
