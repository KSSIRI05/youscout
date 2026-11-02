package ma.youscout.feed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Le service Feed consomme les publications et conserve sa propre projection. */
@SpringBootApplication
@EnableConfigurationProperties({FeedIngestionProperties.class, RelationIngestionProperties.class})
public class FeedApplication {
    public static void main(String[] args) { SpringApplication.run(FeedApplication.class, args); }
}
