package ma.youscout.mediaworker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("worker.ingestion")
public record IngestionProperties(boolean enabled, @NotBlank String bootstrapServers,
        @Pattern(regexp = "[a-zA-Z0-9._-]{1,249}") String topic,
        @Pattern(regexp = "[a-zA-Z0-9._-]{1,200}") String groupId) {}
