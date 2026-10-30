package ma.youscout.mediaworker;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Limites bornées pour qu'une tentative entière reste inférieure au lease de dix minutes. */
@Validated
@ConfigurationProperties("worker.processing")
public record ProcessingProperties(boolean enabled, @NotBlank String bucket, @NotBlank String region,
        String profile, @Min(1) @Max(104857600) long maxBytes,
        @Min(1) @Max(180) int maxDurationSeconds, @Min(1) @Max(180) int transcodeSeconds,
        @NotBlank String ffmpeg, @NotBlank String ffprobe) {}
