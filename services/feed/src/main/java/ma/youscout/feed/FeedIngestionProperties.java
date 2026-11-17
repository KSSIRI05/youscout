package ma.youscout.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("feed.ingestion")
record FeedIngestionProperties(boolean enabled, String bootstrapServers, String topic, String groupId) {}
