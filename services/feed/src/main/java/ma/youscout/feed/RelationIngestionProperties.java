package ma.youscout.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("feed.relations")
record RelationIngestionProperties(boolean enabled, String bootstrapServers, String topic, String groupId) {}
