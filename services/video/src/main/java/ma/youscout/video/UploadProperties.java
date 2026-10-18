package ma.youscout.video;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Limites de ce premier upload PUT ; le contrôle du fichier réel viendra à la confirmation. */
@Validated
@ConfigurationProperties("youscout.uploads")
public record UploadProperties(@Min(1) @Max(5368709120L) long maxSizeBytes,
                               @Min(30) @Max(900) int urlLifetimeSeconds) {}
