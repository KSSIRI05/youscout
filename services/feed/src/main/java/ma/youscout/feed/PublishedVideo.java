package ma.youscout.feed;

import java.time.Instant;
import java.util.UUID;

record PublishedVideo(UUID eventId, UUID videoId, String ownerSubject, String title, Instant publishedAt,
                      java.util.List<FeedTag> skills, java.util.List<FeedTag> hashtags, String fingerprint) {}
record FeedTag(UUID id, String name) {}
