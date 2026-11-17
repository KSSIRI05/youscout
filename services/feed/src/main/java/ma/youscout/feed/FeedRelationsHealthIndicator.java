package ma.youscout.feed;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("feedRelations")
class FeedRelationsHealthIndicator implements HealthIndicator {
    private final FeedRelationsProjection projection;

    FeedRelationsHealthIndicator(FeedRelationsProjection projection) {
        this.projection = projection;
    }

    @Override public Health health() {
        return projection.available()
                ? Health.up().build()
                : Health.down().withDetail("state", "snapshot-incomplete-or-consumer-stale").build();
    }
}
